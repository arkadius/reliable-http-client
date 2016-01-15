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

import java.time.{Duration => JDuration, Instant}

import akka.actor._
import akka.pattern._
import org.slf4j.LoggerFactory
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.{Correlated, WithRetryingHistory}
import rhttpc.transport._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class ReliableProxy[Request, Response](subscriberForConsumer: ActorRef => Subscriber[WithRetryingHistory[Correlated[Request]]],
                                       requestPublisher: Publisher[WithRetryingHistory[Correlated[Request]]],
                                       send: Correlated[Request] => Future[Response],
                                       failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                                       handleResponse: Correlated[Try[Response]] => Future[Unit],
                                       additionalCloseAction: => Future[Unit])
                                      (implicit actorSystem: ActorSystem) {
  
  private lazy val logger = LoggerFactory.getLogger(getClass)

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case withHistory: WithRetryingHistory[_] =>
        try {
          val castedWithHistory = withHistory.asInstanceOf[WithRetryingHistory[Correlated[Request]]]
          (for {
            tryResponse <- send(castedWithHistory.msg).map(Success(_)).recover {
              case NonFatal(ex) => Failure(ex)
            }
            result <- tryResponse match {
              case Success(response) =>
                logger.debug(s"Success response for ${castedWithHistory.msg.correlationId}")
                handleResponse(Correlated(Success(response), castedWithHistory.msg.correlationId))
              case Failure(ex) =>
                logger.error(s"Failure response for ${castedWithHistory.msg.correlationId}")
                handleFailure(castedWithHistory, ex)
            }
          } yield result) pipeTo sender()
        } catch {
          case NonFatal(ex) =>
            sender() ! Status.Failure(ex)
        }
    }

    private def handleFailure(withHistory: WithRetryingHistory[Correlated[Request]], failure: Throwable): Future[Unit] = {
      val strategy = failureHandleStrategyChooser.choose(
        withHistory.attempts,
        withHistory.history.lastOption.flatMap(_.plannedDelay).map(_.toMillis.millis)
      )
      strategy match {
        case Retry(delay) =>
          logger.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will retry in $delay")
          requestPublisher.publish(DelayedMessage(withHistory.withNextAttempt(Instant.now, JDuration.ofMillis(delay.toMillis)), delay))
        case SendToDLQ =>
          logger.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will move to DLQ")
          val exhaustedRetryError = ExhaustedRetry(failure)
          handleResponse(Correlated(Failure(exhaustedRetryError), withHistory.msg.correlationId))
          Future.failed(exhaustedRetryError)
        case Handle =>
          logger.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will handle")
          handleResponse(Correlated(Failure(failure), withHistory.msg.correlationId))
        case Skip =>
          logger.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will skip")
          Future.successful(Unit)
      }
    }
  }))

  private val subscriber = subscriberForConsumer(consumingActor)

  def run() {
    subscriber.run()
  }
  
  def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    recovered(subscriber.stop(), "stopping request subscriber")
    recoveredFuture(gracefulStop(consumingActor, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Request consumer actor hasn't been stopped correctly")
    ), "stopping request consumer actor").flatMap(_ => additionalCloseAction)
  }
  
}

case object NonSuccessResponse extends Exception("Response was recognized as non-success")

case class ExhaustedRetry(message: String, cause: Throwable) extends Exception(message, cause) with RejectingMessage

object ExhaustedRetry {
  def apply(cause: Throwable): ExhaustedRetry = ExhaustedRetry(s"Exhausted retry. Message will be moved to DLQ.", cause)
}

case class ReliableProxyFactory(implicit actorSystem: ActorSystem) {

  def publishingResponses[Request, Response](send: Correlated[Request] => Future[Response],
                                             batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                             queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                             retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                                             additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                                            (implicit responseRequestTransport: PubSubTransport[Correlated[Try[Response]], WithRetryingHistory[Correlated[Request]]] with WithInstantPublisher,
                                             requestPublisherTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Any] with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    val responsePublisher = responseRequestTransport.publisher(OutboundQueueData(prepareResponseQueueName(queuesPrefix)))
    def publisherCloseAction = {
      recovered(responsePublisher.close(), "stopping response publisher")
      additionalCloseAction
    }
    create(
      send = send,
      handleResponse = PublishMsg(responsePublisher),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalCloseAction = publisherCloseAction
    )
  }

  def skippingResponses[Request, Response](send: Correlated[Request] => Future[Response],
                                           batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                           queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                           retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                                           additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                                          (implicit requestSubscriberTransport: PubSubTransport[Nothing, WithRetryingHistory[Correlated[Request]]],
                                           requestPublisherTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Any] with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    create(
      send = send,
      handleResponse = SkipMsg,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy,
      additionalCloseAction = additionalCloseAction
    )
  }

  def create[Request, Response](send: Correlated[Request] => Future[Response],
                                handleResponse: Correlated[Try[Response]] => Future[Unit],
                                batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                                additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                               (implicit requestSubscriberTransport: PubSubTransport[Nothing, WithRetryingHistory[Correlated[Request]]],
                                requestPublisherTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Any] with WithDelayedPublisher):
  ReliableProxy[Request, Response] = {

    new ReliableProxy(
      subscriberForConsumer = prepareSubscriber(requestSubscriberTransport, batchSize, queuesPrefix),
      requestPublisher = requestPublisherTransport.publisher(prepareRequestPublisherQueueData(queuesPrefix)),
      send = send,
      failureHandleStrategyChooser = retryStrategy,
      handleResponse = handleResponse,
      additionalCloseAction = additionalCloseAction
    )
  }

  private def prepareSubscriber[Request](transport: PubSubTransport[Nothing, WithRetryingHistory[Correlated[Request]]], batchSize: Int, queuesPrefix: String)
                                        (implicit actorSystem: ActorSystem):
  (ActorRef) => Subscriber[WithRetryingHistory[Correlated[Request]]] =
    transport.subscriber(InboundQueueData(prepareRequestQueueName(queuesPrefix), batchSize), _)


}