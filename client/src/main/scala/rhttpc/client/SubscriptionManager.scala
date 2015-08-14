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
import akka.pattern._
import akka.util.Timeout
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait SubscriptionManager {
  def run(): Unit
  
  def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Future[Unit]

  def stop()(implicit ec: ExecutionContext): Future[Unit]
}

private[client] trait SubscriptionInternalManagement {
  def registerPromise(subscription: SubscriptionOnResponse): Unit

  def abort(subscription: SubscriptionOnResponse): Unit
}

object SubscriptionManager {
  private[client] def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_, _]): SubscriptionManager with SubscriptionInternalManagement = {
    new SubscriptionManagerImpl()
  }
}

private[client] class SubscriptionManagerImpl (implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_, _])
  extends SubscriptionManager with SubscriptionInternalManagement {

  private val subMgr = actorFactory.actorOf(Props[SubscriptionManagerActor], "subscription-manager")

  private val transportSub = transport.subscriber("rhttpc-response", subMgr)

  override def run(): Unit = {
    transportSub.run()
  }

  override def registerPromise(subscription: SubscriptionOnResponse): Unit = {
    subMgr ! RegisterSubscriptionPromise(subscription)
  }

  override def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Future[Unit] = {
    implicit val timeout = Timeout(10 seconds)
    (subMgr ? ConfirmOrRegisterSubscription(subscription, consumer)).mapTo[Unit]
  }

  override def abort(subscription: SubscriptionOnResponse): Unit = {
    subMgr ! AbortSubscription(subscription)
  }

  override def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      _ <- transportSub.stop()
      smaStopped <- gracefulStop(subMgr, 10 seconds).map(stopped =>
        if (!stopped)
          throw new IllegalStateException("Subscription manager hasn't been stopped correctly")
      )
    } yield smaStopped
  }
}

case class SubscriptionOnResponse(correlationId: String)

trait SubscriptionsHolder extends SubscriptionPromiseRegistrationListener {

  private implicit def ec: ExecutionContext = context.dispatcher

  protected def subscriptionManager: SubscriptionManager

  protected var subscriptions: Set[SubscriptionOnResponse] = Set.empty

  protected def registerSubscriptions(subs: Set[SubscriptionOnResponse]): Future[Set[Unit]] = {
    subscriptions ++= subs
    Future.sequence(subscriptions.map(subscriptionManager.confirmOrRegister(_, self)))
  }

  override private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {
    // FIXME
  }

  protected val handleRegisterSubscription: Receive = {
    case DoConfirmSubscription(subscription) =>
      subscriptions = subscriptions + subscription
      stateChanged()
      subscriptionManager.confirmOrRegister(subscription, self)
  }

  protected val handleMessageFromSubscription: Receive = {
    case MessageFromSubscription(msg, subscription) =>
      subscriptions = subscriptions - subscription
      stateChanged()
      self ! msg
  }

  def stateChanged(): Unit // FIXME state should be saved only onTransiton when we got subscriptions for all requests
}

trait SubscriptionPromiseRegistrationListener extends Actor {
  private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit
}

case class MessageFromSubscription(msg: Any, subscription: SubscriptionOnResponse)
