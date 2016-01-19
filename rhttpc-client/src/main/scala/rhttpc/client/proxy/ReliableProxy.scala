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
import rhttpc.client.Recovered._
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.Correlated
import rhttpc.transport._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class ReliableProxy[Request, Response](subscriberForConsumer: ActorRef => Subscriber[Correlated[Request]],
                                       requestPublisher: Publisher[Correlated[Request]],
                                       send: Correlated[Request] => Future[Response],
                                       failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                                       handleResponse: Correlated[Try[Response]] => Future[Unit],
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
        val casted = correlated.asInstanceOf[Correlated[Request]]
        (for {
          tryResponse <- send(casted).map(Success(_)).recover {
            case NonFatal(ex) => Failure(ex)
          }
          result <- tryResponse match {
            case Success(response) =>
              logger.debug(s"Success response for ${casted.correlationId}")
              handleResponse(Correlated(Success(response), casted.correlationId))
            case Failure(ex) =>
              logger.error(s"Failure response for ${casted.correlationId}")
              handleFailure(casted, attemptsSoFar, lastPlannedDelay, ex)
          }
        } yield result) pipeTo sender()
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
          handleResponse(Correlated(Failure(exhaustedRetryError), correlated.correlationId))
          Future.failed(exhaustedRetryError)
        case Handle =>
          logger.debug(s"Attempts so far: $attemptsSoFar for ${correlated.correlationId}, will handle")
          handleResponse(Correlated(Failure(failure), correlated.correlationId))
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
    recovered(subscriber.stop(), "stopping request subscriber")
    recoveredFuture(gracefulStop(consumingActor, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Request consumer actor hasn't been stopped correctly")
    ), "stopping request consumer actor")
      .map(_ => recovered(requestPublisher.stop(), "stopping request publisher"))
      .flatMap(_ => additionalStopAction)
  }
  
}

case object NonSuccessResponse extends Exception("Response was recognized as non-success")

case class ExhaustedRetry(message: String, cause: Throwable) extends Exception(message, cause) with RejectingMessage

object ExhaustedRetry {
  def apply(cause: Throwable): ExhaustedRetry = ExhaustedRetry(s"Exhausted retry. Message will be moved to DLQ.", cause)
}

case class ReliableProxyFactory(implicit actorSystem: ActorSystem) {

  private lazy val config = ConfigParser.parse(actorSystem)
  
  def publishingResponses[Request, Response](send: Correlated[Request] => Future[Response],
                                             batchSize: Int = config.batchSize,
                                             queuesPrefix: String = config.queuesPrefix,
                                             retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                             additionalStartAction: => Unit = {},
                                             additionalStopAction: => Future[Unit] = Future.successful(Unit))
                                            (implicit responseRequestTransport: PubSubTransport[Correlated[Try[Response]], Correlated[Request]] with WithInstantPublisher,
                                             requestPublisherTransport: PubSubTransport[Correlated[Request], Any] with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    val responsePublisher = responseRequestTransport.publisher(OutboundQueueData(QueuesNaming.prepareResponseQueueName(queuesPrefix)))
    def startAdditional() = {
      additionalStartAction
      responsePublisher.start()
    }
    def stopAdditional = {
      recovered(responsePublisher.stop(), "stopping response publisher")
      additionalStopAction
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
                                          (implicit requestSubscriberTransport: PubSubTransport[Nothing, Correlated[Request]],
                                           requestPublisherTransport: PubSubTransport[Correlated[Request], Any] with WithDelayedPublisher):
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
                                handleResponse: Correlated[Try[Response]] => Future[Unit],
                                batchSize: Int = config.batchSize,
                                queuesPrefix: String = config.queuesPrefix,
                                retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                additionalStartAction: => Unit = {},
                                additionalStopAction: => Future[Unit] = Future.successful(Unit))
                               (implicit requestSubscriberTransport: PubSubTransport[Nothing, Correlated[Request]],
                                requestPublisherTransport: PubSubTransport[Correlated[Request], Any] with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    new ReliableProxy(
      subscriberForConsumer = prepareSubscriber(requestSubscriberTransport, batchSize, queuesPrefix),
      requestPublisher = requestPublisherTransport.publisher(prepareRequestPublisherQueueData(queuesPrefix)),
      send = send,
      failureHandleStrategyChooser = retryStrategy,
      handleResponse = handleResponse,
      additionalStartAction = additionalStartAction,
      additionalStopAction = additionalStopAction
    )
  }

  private def prepareSubscriber[Request](transport: PubSubTransport[Nothing, Correlated[Request]], batchSize: Int, queuesPrefix: String)
                                        (implicit actorSystem: ActorSystem):
  (ActorRef) => Subscriber[Correlated[Request]] =
    transport.fullMessageSubscriber(InboundQueueData(QueuesNaming.prepareRequestQueueName(queuesPrefix), batchSize), _)


}