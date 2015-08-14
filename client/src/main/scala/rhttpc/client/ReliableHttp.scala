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

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import akka.pattern.PipeableFuture
import akka.util.Timeout
import org.slf4j.LoggerFactory
import rhttpc.api.Correlated
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

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
    new PublicationPromise(subscription, acknowledgeFuture)
  }
}

object ReliableHttp {
  def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[Correlated[HttpRequest], Correlated[HttpResponse]]): ReliableClient[HttpRequest] = {
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr)
  }
}

class PublicationPromise(subscription: SubscriptionOnResponse, acknowledgeFuture: Future[DoConfirmSubscription]) {
  def pipeTo(holder: SubscriptionsHolder)(implicit ec: ExecutionContext): Unit = {
    // we can notice about promise registered - message won't be consumed before RegisterSubscriptionPromise in actor because of mailbox processing in order
    holder.subscriptionPromiseRegistered(subscription)
    new PipeableFuture[DoConfirmSubscription](acknowledgeFuture).pipeTo(holder.self)
//    acknowledgeFuture.pipeTo(holder.self) // have no idea why it not compile?
  }
}

case class DoConfirmSubscription(subscription: SubscriptionOnResponse)

class NoAckException(request: HttpRequest) extends Exception(s"No acknowledge for request: $request")