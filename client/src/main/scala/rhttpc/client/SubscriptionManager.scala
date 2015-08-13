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

import akka.actor._
import akka.http.scaladsl.model.HttpResponse
import akka.pattern._
import akka.util.Timeout
import rhttpc.api.Correlated
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait SubscriptionManager {
  def run(): Unit

  def register(subscription: SubscriptionOnResponse, consumer: ActorRef): Future[SubscriptionRegistered]
}

object SubscriptionManager {
  def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_, Correlated[HttpResponse]]): SubscriptionManager = {
    new SubscriptionManagerImpl()
  }
}

class SubscriptionManagerImpl(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_, Correlated[HttpResponse]]) extends SubscriptionManager {
  private val subMgr = actorFactory.actorOf(Props[SubscriptionManagerActor], "subscription-manager")

  private val transportSub = transport.subscription("rhttpc-response", subMgr)

  override def run(): Unit = {
    transportSub.run()
  }

  override def register(subscription: SubscriptionOnResponse, consumer: ActorRef): Future[SubscriptionRegistered] = {
    implicit val timeout = Timeout(10 seconds)
    (subMgr ? RegisterSubscription(subscription, consumer)).mapTo[SubscriptionRegistered]
  }
}

case class SubscriptionOnResponse(correlationId: String)

trait SubscriptionsHolder { this: Actor =>

  private implicit def ec: ExecutionContext = context.dispatcher

  protected def subscriptionManager: SubscriptionManager

  protected var subscriptions: Set[SubscriptionOnResponse] = Set.empty

  protected def registerSubscriptions(subs: Set[SubscriptionOnResponse]): Future[Set[SubscriptionRegistered]] = {
    subscriptions ++= subs
    Future.sequence(subscriptions.map(subscriptionManager.register(_, self)))
  }

  private[rhttpc] def failedRequest(ex: Throwable, subscription: SubscriptionOnResponse) = {
    self ! Status.Failure(ex)
  }

  protected val handleRegisterSubscription: Receive = {
    case DoRegisterSubscription(subscription) =>
      subscriptions = subscriptions + subscription
      stateChanged()
      subscriptionManager.register(subscription, self)
  }

  protected val handleMessageFromSubscription: Receive = {
    case MessageFromSubscription(msg, subscription) =>
      subscriptions = subscriptions - subscription
      stateChanged()
      self ! msg
  }

  def stateChanged(): Unit // FIXME state should be saved only onTransiton when we got subscriptions for all requests
}

case class MessageFromSubscription(msg: Any, subscription: SubscriptionOnResponse)
