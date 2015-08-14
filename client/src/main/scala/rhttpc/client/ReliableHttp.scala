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
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.pattern._
import akka.util.Timeout
import org.slf4j.LoggerFactory
import rhttpc.api.Correlated
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Failure

object ReliableHttp {
  def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[Correlated[HttpRequest], Correlated[HttpResponse]]): ReliableClient[HttpRequest] = {
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr)
  }
}

class ReliableClient[Request](subMgr: SubscriptionManager with SubscriptionInternalManagement)(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[Correlated[Request], _]) {
  private lazy val log = LoggerFactory.getLogger(getClass)

  def subscriptionManager: SubscriptionManager = subMgr

  private val publisher = transport.publisher("rhttpc-request")

  def send(request: Request)(implicit ec: ExecutionContext): PublicationPromise = {
    val correlationId = UUID.randomUUID().toString
    implicit val timeout = Timeout(10 seconds)
    val correlated = Correlated(request, correlationId)
    val subscription = SubscriptionOnResponse(correlationId)
    subMgr.registerPromise(subscription)
    val acknowledgeFuture = publisher.publish(correlated).map { _ =>
      log.debug(s"Request: $correlated successfully acknowledged")
      DoConfirmSubscription(subscription)
    }
    acknowledgeFuture.onFailure {
      case ex =>
        log.error(s"Request: $correlated acknowledgement failure", ex)
        subMgr.abort(subscription)
    }
    new PublicationPromise(subscription, acknowledgeFuture, subMgr)
  }

  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      _ <- publisher.close()
      subMgrStopped <- subscriptionManager.stop()
    } yield subMgrStopped
  }
}

class PublicationPromise(subscription: SubscriptionOnResponse, acknowledgeFuture: Future[DoConfirmSubscription], subscriptionManager: SubscriptionManager) {
  def pipeTo(holder: SubscriptionPromiseRegistrationListener)(implicit ec: ExecutionContext): Unit = {
    // we can notice about promise registered - message won't be consumed before RegisterSubscriptionPromise in actor because of mailbox processing in order
    holder.subscriptionPromiseRegistered(subscription)
    new PipeableFuture[DoConfirmSubscription](acknowledgeFuture).pipeTo(holder.self)
//    acknowledgeFuture.pipeTo(holder.self) // have no idea why it not compile?
  }

  def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any] = {
    import system.dispatcher
    val promise = Promise[Any]()
    system.actorOf(Props(new SubscriptionPromiseRegistrationListener {
      override private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {}

      override def receive: Actor.Receive = {
        case DoConfirmSubscription(sub) =>
          assert(sub == subscription, "should be confirmed about self subscription")
          subscriptionManager.confirmOrRegister(subscription, self)
          context.become(waitForMessage)
      }

      private val waitForMessage: Receive = {
        case Status.Failure(ex) =>
          promise.failure(ex)
          context.stop(self)
        case msg =>
          promise.success(msg)
          context.stop(self)
      }

      pipeTo(this)
    }))
    val f = system.scheduler.scheduleOnce(timeout.duration) {
      promise tryComplete Failure(new TimeoutException(s"Timed out on waiting on response from subscription"))
    }
    promise.future onComplete { _ => f.cancel() }
    promise.future
  }
}

case class DoConfirmSubscription(subscription: SubscriptionOnResponse)

class NoAckException(request: HttpRequest) extends Exception(s"No acknowledge for request: $request")