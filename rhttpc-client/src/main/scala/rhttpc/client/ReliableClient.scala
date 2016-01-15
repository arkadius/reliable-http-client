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
package rhttpc.client

import java.time.Instant
import java.util.UUID

import akka.actor._
import org.slf4j.LoggerFactory
import rhttpc.client.config.ConfigParser
import rhttpc.client.consume.MessageConsumerFactory
import rhttpc.client.protocol.{Correlated, WithRetryingHistory}
import rhttpc.client.proxy.{FailureResponseHandleStrategyChooser, ReliableProxyFactory}
import rhttpc.client.subscription.{SubscriptionManager, SubscriptionManagerFactory, WithSubscriptionManager}
import rhttpc.transport.{WithInstantPublisher, PubSubTransport, Publisher, WithDelayedPublisher}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

class ReliableClient[Request, SendResult](publisher: Publisher[WithRetryingHistory[Correlated[Request]]],
                                          publicationHandler: PublicationHandler[SendResult],
                                          additionalRunAction: => Unit,
                                          additionalCloseAction: => Future[Unit]) {

  private lazy val logger = LoggerFactory.getLogger(getClass)

  def run() = {
    additionalRunAction
  }

  def send(request: Request)(implicit ec: ExecutionContext): SendResult = {
    val correlationId = UUID.randomUUID().toString
    val correlated = Correlated(request, correlationId)
    val withHistory = WithRetryingHistory.firstAttempt(correlated, Instant.now())
    publicationHandler.beforePublication(correlationId)
    val publicationAckFuture = publisher.publish(withHistory).map { _ =>
      logger.debug(s"Request: $correlationId successfully acknowledged")
    }.recoverWith {
      case NonFatal(ex) =>
        logger.error(s"Request: $correlationId acknowledgement failure", ex)
        Future.failed(NoAckException(request, ex))
    }
    publicationHandler.processPublicationAck(correlationId, publicationAckFuture)
  }

  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    recovered(publisher.close(), "closing request publisher")
    additionalCloseAction
  }
}

case class NoAckException(request: Any, cause: Throwable) extends Exception(s"No acknowledge for request: $request", cause)

case class ReliableClientFactory(implicit actorSystem: ActorSystem) {
  import actorSystem.dispatcher

  def inOutWithSubscriptions[Request, Response](send: Correlated[Request] => Future[Response],
                                                batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                                queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                                retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                                                additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                                               (implicit requestResponseTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Correlated[Try[Response]]] with WithDelayedPublisher,
                                                responseRequestTransport: PubSubTransport[Correlated[Try[Response]], WithRetryingHistory[Correlated[Request]]] with WithInstantPublisher): InOutReliableClient[Request] = {
    val proxy = ReliableProxyFactory().publishingResponses(
      send = send,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
    val subMgr = SubscriptionManagerFactory().create[Response](
      batchSize = batchSize,
      queuesPrefix = queuesPrefix
    )
    val requestPublisher = requestResponseTransport.publisher(prepareRequestPublisherQueueData(queuesPrefix))
    def runAdditional() = {
      subMgr.run()
      proxy.run()
    }
    def closeAdditional = {
      recoveredFuture(proxy.stop(), "stopping proxy")
        .flatMap(_ => recoveredFuture(subMgr.stop(), "stopping subscription manager"))
        .flatMap(_ => additionalCloseAction)
    }
    new ReliableClient(
      publisher = requestPublisher,
      publicationHandler = subMgr,
      additionalRunAction = runAdditional(),
      additionalCloseAction = closeAdditional
    ) with WithSubscriptionManager {
      override def subscriptionManager: SubscriptionManager = subMgr
    }
  }

  def inOut[Request, Response](send: Correlated[Request] => Future[Response],
                               handleResponse: Correlated[Try[Response]] => Future[Unit],
                               batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                               queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                               retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                               additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                              (implicit requestPublisherTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Correlated[Try[Response]]] with WithDelayedPublisher,
                               requestSubscriberTransport: PubSubTransport[Correlated[Try[Response]], WithRetryingHistory[Correlated[Request]]] with WithInstantPublisher): InOnlyReliableClient[Request] = {
    val proxy = ReliableProxyFactory().publishingResponses(
      send = send,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
    val responseConsumer = MessageConsumerFactory().create[Response](
      handleMessage = handleResponse,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix
    )
    def runAdditional() = {
      responseConsumer.run()
      proxy.run()
    }
    def closeAdditional = {
      recoveredFuture(proxy.stop(), "stopping proxy")
        .flatMap(_ => recoveredFuture(responseConsumer.stop(), "stopping response consumer"))
        .flatMap(_ => additionalCloseAction)
    }
    create(
      publicationHandler = StraightforwardPublicationHandler,
      additionalRunAction = runAdditional(),
      additionalCloseAction = closeAdditional
    )
  }

  def inOnly[Request](send: Correlated[Request] => Future[Unit],
                      batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                      queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                      retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy,
                      additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                     (implicit requestPublisherTransport: PubSubTransport[WithRetryingHistory[Correlated[Request]], Any] with WithDelayedPublisher,
                      requestSubscriberTransport: PubSubTransport[Nothing, WithRetryingHistory[Correlated[Request]]] with WithInstantPublisher): InOnlyReliableClient[Request] = {
    val proxy = ReliableProxyFactory().skippingResponses(
      send = send,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
    def closeAdditional = {
      recoveredFuture(proxy.stop(), "stopping proxy")
        .flatMap(_ => additionalCloseAction)
    }
    create(
      publicationHandler = StraightforwardPublicationHandler,
      additionalRunAction = proxy.run(),
      additionalCloseAction = closeAdditional
    )
  }

  def create[Request, SendResult](publicationHandler: PublicationHandler[SendResult],
                                  batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                  queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                  additionalRunAction: => Unit = {},
                                  additionalCloseAction: => Future[Unit] = Future.successful(Unit))
                                 (implicit transport: PubSubTransport[WithRetryingHistory[Correlated[Request]], _] with WithDelayedPublisher): ReliableClient[Request, SendResult] = {
    val requestPublisher = transport.publisher(prepareRequestPublisherQueueData(queuesPrefix))
    new ReliableClient(
      publisher = requestPublisher,
      publicationHandler = publicationHandler,
      additionalRunAction = additionalRunAction,
      additionalCloseAction = additionalCloseAction
    )
  }

}