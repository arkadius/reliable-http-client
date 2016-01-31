/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rhttpc.client.proxy

import akka.actor._
import akka.pattern._
import org.slf4j.LoggerFactory
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.{Correlated, Exchange, FailureExchange, SuccessExchange}
import rhttpc.transport._
import rhttpc.utils.Recovered
import rhttpc.utils.Recovered._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class ReliableProxy[Request, Response](subscriberForConsumer: ActorRef => Subscriber[Correlated[Request]],
                                       requestPublisher: Publisher[Correlated[Request]],
                                       send: Correlated[Request] => Future[Response],
                                       failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                                       handleResponse: Correlated[Exchange[Request, Response]] => Future[Unit],
                                       additionalStartAction: => Unit,
                                       additionalStopAction: => Future[Unit])
                                      (implicit actorSystem: ActorSystem) {
  
  private lazy val logger = LoggerFactory.getLogger(getClass)

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case DelayedMessage(content, delay, attempt) =>
        handleRequest(content.asInstanceOf[Correlated[Request]], attempt, Some(delay))
      case message: Message[_] =>
        handleRequest(message.content.asInstanceOf[Correlated[Request]], 1, None)

    }

    private def handleRequest(correlated: Correlated[Request], attemptsSoFar: Int, lastPlannedDelay: Option[FiniteDuration]) = {
      try {
        send(correlated).map(Success(_)).recover {
          case NonFatal(ex) => Failure(ex)
        }.flatMap {
          case Success(response) =>
            logger.debug(s"Success response for ${correlated.correlationId}")
            handleResponse(Correlated(SuccessExchange(correlated.msg, response), correlated.correlationId))
          case Failure(ex) =>
            logger.error(s"Failure response for ${correlated.correlationId}", ex)
            handleFailure(correlated, attemptsSoFar, lastPlannedDelay, ex)
        }.pipeTo(sender())
      } catch {
        case NonFatal(ex) =>
          sender() ! Status.Failure(ex)
      }
    }

    private def handleFailure(correlated: Correlated[Request], attemptsSoFar: Int, lastPlannedDelay: Option[FiniteDuration], failure: Throwable): Future[Unit] = {
      val strategy = failureHandleStrategyChooser.choose(attemptsSoFar, lastPlannedDelay)
      strategy match {
        case Retry(delay) =>
          logger.debug(s"Attempts so far: $attemptsSoFar for ${correlated.correlationId}, will retry in $delay")
          requestPublisher.publish(DelayedMessage(correlated, delay, attempt = attemptsSoFar + 1))
        case SendToDLQ =>
          logger.debug(s"Attempts so far: $attemptsSoFar for ${correlated.correlationId}, will move to DLQ")
          val exhaustedRetryError = ExhaustedRetry(failure)
          handleResponse(Correlated(FailureExchange(correlated.msg, exhaustedRetryError), correlated.correlationId))
          Future.failed(exhaustedRetryError)
        case Handle =>
          logger.debug(s"Attempts so far: $attemptsSoFar for ${correlated.correlationId}, will handle")
          handleResponse(Correlated(FailureExchange(correlated.msg, failure), correlated.correlationId))
        case Skip =>
          logger.debug(s"Attempts so far: $attemptsSoFar for ${correlated.correlationId}, will skip")
          Future.successful(Unit)
      }
    }
  }))

  private val subscriber = subscriberForConsumer(consumingActor)

  def start() {
    additionalStartAction
    requestPublisher.start()
    subscriber.start()
  }
  
  def stop(): Future[Unit] = {
    import actorSystem.dispatcher
    recoveredFuture("stopping request subscriber", subscriber.stop())
      .flatMap(_ => recoveredFuture("stopping request consumer actor", gracefulStop(consumingActor, 30 seconds).map(_ => Unit)))
      .flatMap(_ => recoveredFuture("stopping request publisher", requestPublisher.stop()))
      .flatMap(_ => recoveredFuture("additional stop action", additionalStopAction))
  }
  
}

case object NonSuccessResponse extends Exception("Response was recognized as non-success")

case class ExhaustedRetry(message: String, cause: Throwable) extends Exception(message, cause) with RejectingMessage

object ExhaustedRetry {
  def apply(cause: Throwable): ExhaustedRetry = ExhaustedRetry(s"Exhausted retry. Message will be moved to DLQ.", cause)
}

case class ReliableProxyFactory(implicit actorSystem: ActorSystem) {

  import actorSystem.dispatcher

  private lazy val config = ConfigParser.parse(actorSystem)
  
  def publishingResponses[Request, Response](send: Correlated[Request] => Future[Response],
                                             batchSize: Int = config.batchSize,
                                             queuesPrefix: String = config.queuesPrefix,
                                             retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                             additionalStartAction: => Unit = {},
                                             additionalStopAction: => Future[Unit] = Future.successful(Unit))
                                            (implicit responseRequestTransport: PubSubTransport with WithInstantPublisher,
                                             requestPublisherTransport: PubSubTransport with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    val responsePublisher = responseRequestTransport.publisher[Correlated[Exchange[Request, Response]]](
      OutboundQueueData(QueuesNaming.prepareResponseQueueName(queuesPrefix))
    )
    def startAdditional() = {
      additionalStartAction
      responsePublisher.start()
    }
    def stopAdditional = {
      recoveredFuture("stopping response publisher", responsePublisher.stop())
        .flatMap(_ => recoveredFuture("additional stop action", additionalStopAction))
    }
    create(
      send = send,
      handleResponse = PublishMsg(responsePublisher),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalStartAction = startAdditional(),
      additionalStopAction = stopAdditional
    )
  }

  def skippingResponses[Request, Response](send: Correlated[Request] => Future[Response],
                                           batchSize: Int = config.batchSize,
                                           queuesPrefix: String = config.queuesPrefix,
                                           retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                           additionalStartAction: => Unit = {},
                                           additionalStopAction: => Future[Unit] = Future.successful(Unit))
                                          (implicit transport: PubSubTransport with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    create(
      send = send,
      handleResponse = SkipMsg,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalStartAction = additionalStartAction,
      additionalStopAction = additionalStopAction
    )
  }

  def create[Request, Response](send: Correlated[Request] => Future[Response],
                                handleResponse: Correlated[Exchange[Request, Response]] => Future[Unit],
                                batchSize: Int = config.batchSize,
                                queuesPrefix: String = config.queuesPrefix,
                                retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                additionalStartAction: => Unit = {},
                                additionalStopAction: => Future[Unit] = Future.successful(Unit))
                               (implicit transport: PubSubTransport with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    new ReliableProxy(
      subscriberForConsumer = prepareSubscriber(transport, batchSize, queuesPrefix),
      requestPublisher = transport.publisher[Correlated[Request]](prepareRequestPublisherQueueData(queuesPrefix)),
      send = send,
      failureHandleStrategyChooser = retryStrategy,
      handleResponse = handleResponse,
      additionalStartAction = additionalStartAction,
      additionalStopAction = additionalStopAction
    )
  }

  private def prepareSubscriber[Request](transport: PubSubTransport, batchSize: Int, queuesPrefix: String)
                                        (implicit actorSystem: ActorSystem):
  (ActorRef) => Subscriber[Correlated[Request]] =
    transport.fullMessageSubscriber[Correlated[_]](InboundQueueData(QueuesNaming.prepareRequestQueueName(queuesPrefix), batchSize), _)
      .asInstanceOf[Subscriber[Correlated[Request]]]


}