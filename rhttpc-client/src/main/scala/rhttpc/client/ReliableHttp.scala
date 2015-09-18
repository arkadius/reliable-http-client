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

import java.util.UUID
import java.util.concurrent.TimeoutException

import akka.actor._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern._
import akka.stream.Materializer
import akka.util.Timeout
import com.rabbitmq.client.Connection
import org.slf4j.LoggerFactory
import rhttpc.actor.impl.PromiseSubscriptionCommandsListener
import rhttpc.proxy.ReliableHttpProxy
import rhttpc.transport.PubSubTransport
import rhttpc.transport.amqp._
import rhttpc.transport.api.Correlated
import rhttpc.transport.json4s.Json4sSerializer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Try}

object ReliableHttp {
  def apply()(implicit actorSystem: ActorSystem): ReliableClient[HttpRequest] = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    import Json4sSerializer.formats
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[HttpRequest], Correlated[Try[HttpResponse]]](actorSystem, connection)
    )
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        recovered(super.close(), "closing ReliableHttp").map { _ =>
          connection.close()
        }
      }
    }
  }

  def apply(connection: Connection)(implicit actorSystem: ActorSystem): ReliableClient[HttpRequest] = {
    import Json4sSerializer.formats
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[HttpRequest], Correlated[Try[HttpResponse]]](actorSystem, connection)
    )
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr)
  }

  def withEmbeddedProxy(connection: Connection)(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableClient[HttpRequest] = {
    val proxy = ReliableHttpProxy(connection, batchSize = 10)
    proxy.run()
    import Json4sSerializer.formats
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[HttpRequest], Correlated[Try[HttpResponse]]](actorSystem, connection)
    )
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        for {
          _ <- recovered(super.close(), "closing ReliableHttp")
          proxyCloseResult <- proxy.close()
        } yield proxyCloseResult
      }
    }
  }

  def withEmbeddedProxy()(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableClient[HttpRequest] = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    val proxy = ReliableHttpProxy(connection, batchSize = 10)
    proxy.run()
    import Json4sSerializer.formats
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[HttpRequest], Correlated[Try[HttpResponse]]](actorSystem, connection)
    )
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        for {
          _ <- recovered(super.close(), "closing ReliableHttp")
          _ <- recovered(proxy.close(), "closing ReliableHttpProxy")
        } yield connection.close()
      }
    }
  }

  private def recovered[T](future: Future[T], action: String)
                          (implicit actorSystem: ActorSystem) = {
    import actorSystem.dispatcher
    future.recover {
      case ex =>
        actorSystem.log.error(ex, s"Exception while $action")
    }
  }
}

class ReliableClient[Request](subMgr: SubscriptionManager with SubscriptionInternalManagement)(implicit actorFactory: ActorSystem, transport: PubSubTransport[Correlated[Request]]) {
  private lazy val log = LoggerFactory.getLogger(getClass)

  def subscriptionManager: SubscriptionManager = subMgr

  private val publisher = transport.publisher("rhttpc-request")

  def send(request: Request)(implicit ec: ExecutionContext): ReplyFuture = {
    val correlationId = UUID.randomUUID().toString
    implicit val timeout = Timeout(10 seconds)
    val correlated = Correlated(request, correlationId)
    val subscription = SubscriptionOnResponse(correlationId)
    // we need to registerPromise before publish because message can be consumed before subscription on response registration 
    subMgr.registerPromise(subscription)
    val publicationAckFuture = publisher.publish(correlated).map { _ =>
      log.debug(s"Request: $correlated successfully acknowledged")
      RequestPublished(subscription)
    }
    val abortingIfFailureFuture = publicationAckFuture.recover {
      case ex =>
        log.error(s"Request: $correlated acknowledgement failure", ex)
        subMgr.abort(subscription)
        RequestAborted(subscription, ex)
    }
    new ReplyFuture(subscription, abortingIfFailureFuture)(request, subMgr)
  }

  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    subscriptionManager.stop().recover {
      case ex =>
        log.error("Exception while stopping subscriptionManager", ex)
    }.map { _ =>
      publisher.close()
    }
  }
}

class ReplyFuture(subscription: SubscriptionOnResponse, publicationFuture: Future[PublicationResult])
                 (request: Any, subscriptionManager: SubscriptionManager with SubscriptionInternalManagement) {
  def pipeTo(listener: PublicationListener)(implicit ec: ExecutionContext): Unit = {
    // we can notice about promise registered in this place - message won't be consumed before RegisterSubscriptionPromise
    // in dispatcher actor because of mailbox processing in order
    listener.subscriptionPromiseRegistered(subscription)
    publicationFuture pipeTo listener.self
  }

  def toPublicationFuture(implicit ec: ExecutionContext): Future[Unit.type] = {
    publicationFuture.map {
      case RequestPublished(_) =>
        subscriptionManager.abort(subscription) // we are not interested about response so we need to clean up after registration promise
        Unit
      case RequestAborted(_, ex) =>
        throw new NoAckException(request, ex)
    }
  }

  def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any] = {
    import system.dispatcher
    val promise = Promise[Any]()
    system.actorOf(PromiseSubscriptionCommandsListener.props(this, promise)(request, subscriptionManager))
    val f = system.scheduler.scheduleOnce(timeout.duration) {
      promise tryComplete Failure(new TimeoutException(s"Timed out on waiting on response from subscription"))
    }
    promise.future onComplete { _ => f.cancel() }
    promise.future
  }
}

class NoAckException(request: Any, cause: Throwable) extends Exception(s"No acknowledge for request: $request", cause)