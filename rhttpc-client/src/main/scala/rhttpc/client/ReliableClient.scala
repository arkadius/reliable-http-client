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
import rhttpc.client.protocol.{Correlated, WithRetryingHistory}
import rhttpc.client.subscription.{ReplyFuture, SubscriptionManager, SubscriptionManagerFactory, WithSubscriptionManager}
import rhttpc.transport.{PubSubTransport, Publisher}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal

class ReliableClient[Request, SendResult](publisher: Publisher[WithRetryingHistory[Correlated[Request]]],
                                          publicationHandler: PublicationHandler[SendResult],
                                          additionalCloseAction: => Future[Unit]) {

  private lazy val log = LoggerFactory.getLogger(getClass)

  def run() = {
    publicationHandler.run()
  }

  def send(request: Request)(implicit ec: ExecutionContext): SendResult = {
    val correlationId = UUID.randomUUID().toString
    val correlated = Correlated(request, correlationId)
    val withHistory = WithRetryingHistory.firstAttempt(correlated, Instant.now())
    publicationHandler.beforePublication(correlationId)
    val publicationAckFuture = publisher.publish(withHistory).map { _ =>
      log.debug(s"Request: $correlationId successfully acknowledged")
    }.recoverWith {
      case NonFatal(ex) =>
        log.error(s"Request: $correlationId acknowledgement failure", ex)
        Future.failed(NoAckException(request, ex))
    }
    publicationHandler.processPublicationAck(correlationId, publicationAckFuture)
  }

  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    recoveredFuture(publicationHandler.stop(), "stopping publication handler").flatMap { _ =>
      recovered(publisher.close(), "closing request publisher")
      additionalCloseAction
    }
  }
}

case class NoAckException(request: Any, cause: Throwable) extends Exception(s"No acknowledge for request: $request", cause)

case class ReliableClientFactory(implicit actorSystem: ActorSystem) {

  def create[Request](transport: PubSubTransport[WithRetryingHistory[Correlated[Request]], _],
                      batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                      queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                      additionalCloseAction: => Future[Unit] = Future.successful(Unit)): InOutReliableClient[Request] = {
    val subMgr = SubscriptionManagerFactory().create(transport, batchSize, queuesPrefix)
    val requestPublisher = transport.publisher(prepareRequestPublisherQueueData(queuesPrefix)) 
    new ReliableClient[Request, ReplyFuture](requestPublisher, subMgr, additionalCloseAction) with WithSubscriptionManager {
      override def subscriptionManager: SubscriptionManager = subMgr
    }
  }

}