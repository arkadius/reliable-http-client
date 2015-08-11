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
package reliablehttpc

import akka.actor.{Actor, ActorRef, ActorSystem}

class SubscriptionManager(implicit actorSystem: ActorSystem) {

  def register(subscription: Subscription, consumer: ActorRef) = {
    // FIXME: subscription handle
  }
}

case class Subscription(queue: String, correlationId: String)

class Pipeable(subscription: Subscription) {
  def pipeTo(holder: SubscriptionsHolder): Unit = {
    holder.register(subscription)
  }
}

trait SubscriptionsHolder { this: Actor =>

  protected def subscriptionManager: SubscriptionManager

  protected var subscriptions: Set[Subscription] = Set.empty

  private[reliablehttpc] def register(subscription: Subscription) = {
    subscriptions = subscriptions + subscription
    subscriptionManager.register(subscription, self)
  }

  protected def registerSubscriptions(): Unit = {
    subscriptions.foreach(subscriptionManager.register(_, self))
  }

  protected val handleMessageFromSubscription: Receive = {
    case MessageFromSubscription(msg, subscription) =>
      subscriptions = subscriptions - subscription
      self ! msg
  }
  
}

case class MessageFromSubscription(msg: Any, subscription: Subscription)