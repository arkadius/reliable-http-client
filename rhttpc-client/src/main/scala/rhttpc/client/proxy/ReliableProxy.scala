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
import rhttpc.client.protocol._
import rhttpc.transport._
import rhttpc.utils.Recovered._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class ReliableProxy[Req, Resp](subscriberForConsumer: ActorRef => Subscriber[Correlated[Req]],
                               requestPublisher: Publisher[Correlated[Req]],
                               send: Request[Req] => Future[Resp],
                               failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                               handleResponse: Correlated[Exchange[Req, Resp]] => Future[Unit],
                               additionalStartAction: => Unit,
                               additionalStopAction: => Future[Unit])
                              (implicit actorSystem: ActorSystem) {
  
  private lazy val logger = LoggerFactory.getLogger(getClass)

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case DelayedMessage(content, delay, attempt) =>
        handleRequest(Request(content.asInstanceOf[Correlated[Req]], attempt, delay))
      case message: Message[_] =>
        handleRequest(Request.firstAttempt(message.content.asInstanceOf[Correlated[Req]]))

    }

    private def handleRequest(request: Request[Req]) = {
      try {
        send(request).map(Success(_)).recover {
          case NonFatal(ex) => Failure(ex)
        }.flatMap {
          case Success(response) =>
            logger.debug(s"Success response for ${request.correlationId}")
            handleResponse(Correlated(SuccessExchange(request.msg, response), request.correlationId))
          case Failure(ex) =>
            logger.error(s"Failure response for ${request.correlationId}", ex)
            handleFailure(request, ex)
        }.pipeTo(sender())
      } catch {
        case NonFatal(ex) =>
          sender() ! Status.Failure(ex)
      }
    }

    private def handleFailure(request: Request[Req], failure: Throwable): Future[Unit] = {
      val strategy = failureHandleStrategyChooser.choose(request.attempt, request.lastPlannedDelay)
      strategy match {
        case Retry(delay) =>
          logger.debug(s"Attempts so far: ${request.attempt} for ${request.correlationId}, will retry in $delay")
          requestPublisher.publish(DelayedMessage(request.correlated, delay, request.nextAttempt.attempt))
        case SendToDLQ =>
          logger.debug(s"Attempts so far: ${request.attempt} for ${request.correlationId}, will move to DLQ")
          val exhaustedRetryError = ExhaustedRetry(failure)
          handleResponse(Correlated(FailureExchange(request.msg, exhaustedRetryError), request.correlationId))
          Future.failed(exhaustedRetryError)
        case Handle =>
          logger.debug(s"Attempts so far: ${request.attempt} for ${request.correlationId}, will handle")
          handleResponse(Correlated(FailureExchange(request.msg, failure), request.correlationId))
        case Skip =>
          logger.debug(s"Attempts so far: ${request.attempt} for ${request.correlationId}, will skip")
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

case class ExhaustedRetry(message: String) extends Exception(message) with RejectingMessage

object ExhaustedRetry {
  def apply(cause: Throwable): ExhaustedRetry = ExhaustedRetry(s"Exhausted retry. Message will be moved to DLQ. Cause: ${cause.getMessage}")
}

case class ReliableProxyFactory()(implicit actorSystem: ActorSystem) {

  import actorSystem.dispatcher

  private lazy val config = ConfigParser.parse(actorSystem)
  
  def publishingResponses[Req, Resp](send: Request[Req] => Future[Resp],
                                     batchSize: Int = config.batchSize,
                                     parallelConsumers: Int = config.parallelConsumers,
                                     queuesPrefix: String = config.queuesPrefix,
                                     retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                     additionalStartAction: => Unit = {},
                                     additionalStopAction: => Future[Unit] = Future.successful(Unit))
                                    (implicit transport: PubSubTransport,
                                     reqSerializer: Serializer[Correlated[Req]],
                                     reqDeserializer: Deserializer[Correlated[Req]],
                                     exSerializer: Serializer[Correlated[Exchange[Req, Resp]]]):
  ReliableProxy[Req, Resp] = {

    val responsePublisher = transport.publisher[Correlated[Exchange[Req, Resp]]](
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
      parallelConsumers = parallelConsumers,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalStartAction = startAdditional(),
      additionalStopAction = stopAdditional
    )
  }

  def skippingResponses[Req, Resp](send: Request[Req] => Future[Resp],
                                   batchSize: Int = config.batchSize,
                                   parallelConsumers: Int = config.parallelConsumers,
                                   queuesPrefix: String = config.queuesPrefix,
                                   retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                                   additionalStartAction: => Unit = {},
                                   additionalStopAction: => Future[Unit] = Future.successful(Unit))
                                  (implicit transport: PubSubTransport,
                                   serializer: Serializer[Correlated[Req]],
                                   deserializer: Deserializer[Correlated[Req]]):
  ReliableProxy[Req, Resp] = {

    create(
      send = send,
      handleResponse = SkipMsg,
      batchSize = batchSize,
      parallelConsumers = parallelConsumers,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalStartAction = additionalStartAction,
      additionalStopAction = additionalStopAction
    )
  }

  def create[Req, Resp](send: Request[Req] => Future[Resp],
                        handleResponse: Correlated[Exchange[Req, Resp]] => Future[Unit],
                        batchSize: Int = config.batchSize,
                        parallelConsumers: Int = config.parallelConsumers,
                        queuesPrefix: String = config.queuesPrefix,
                        retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy,
                        additionalStartAction: => Unit = {},
                        additionalStopAction: => Future[Unit] = Future.successful(Unit))
                       (implicit transport: PubSubTransport,
                        serializer: Serializer[Correlated[Req]],
                        deserializer: Deserializer[Correlated[Req]]):
  ReliableProxy[Req, Resp] = {

    new ReliableProxy(
      subscriberForConsumer = prepareSubscriber[Req](transport, batchSize, parallelConsumers, queuesPrefix),
      requestPublisher = transport.publisher[Correlated[Req]](prepareDelayedRequestPublisherQueueData(queuesPrefix)),
      send = send,
      failureHandleStrategyChooser = retryStrategy,
      handleResponse = handleResponse,
      additionalStartAction = additionalStartAction,
      additionalStopAction = additionalStopAction
    )
  }

  private def prepareSubscriber[Request](transport: PubSubTransport, batchSize: Int, parallelConsumers: Int, queuesPrefix: String)
                                        (implicit actorSystem: ActorSystem,
                                         deserializer: Deserializer[Correlated[Request]]):
  (ActorRef) => Subscriber[Correlated[Request]] =
    transport.fullMessageSubscriber[Correlated[Request]](InboundQueueData(QueuesNaming.prepareRequestQueueName(queuesPrefix), batchSize, parallelConsumers), _)


}