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
package rhttpc.client.subscription

import akka.actor.{Actor, Props, Status}

import scala.concurrent.Promise

private class PromiseSubscriptionCommandsListener(pubPromise: ReplyFuture, replyPromise: Promise[Any])
                                                 (subscriptionManager: SubscriptionManager) extends PublicationListener {
  import context.dispatcher

  override def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {}

  override def receive: Actor.Receive = {
    case RequestPublished(sub) =>
      subscriptionManager.confirmOrRegister(sub, self)
      context.become(waitForMessage)
    case RequestAborted(sub, cause) =>
      replyPromise.failure(cause)
      context.stop(self)
  }

  private val waitForMessage: Receive = {
    case MessageFromSubscription(Status.Failure(ex), sub) =>
      replyPromise.failure(ex)
      context.stop(self)
    case MessageFromSubscription(msg, sub) =>
      replyPromise.success(msg)
      context.stop(self)
  }

  pubPromise.pipeTo(this)
}

private[subscription] object PromiseSubscriptionCommandsListener {
  def props(pubPromise: ReplyFuture, replyPromise: Promise[Any])
           (subscriptionManager: SubscriptionManager): Props =
    Props(new PromiseSubscriptionCommandsListener(pubPromise, replyPromise)(subscriptionManager))
}