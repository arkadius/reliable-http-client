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
package rhttpc.actor.impl

import rhttpc.client._

private[rhttpc] trait SubscriptionsHolder[S, D] extends PublicationListener with StateTransitionHandler[S, D] {
  
  protected def subscriptionManager: SubscriptionManager

  private var subscriptionStates: SubscriptionsStateStack = SubscriptionsStateStack()

  override protected def onSubscriptionsOffered(subscriptionsOffered: Set[SubscriptionOnResponse]): Unit = {
    val withRegistered = subscriptionsOffered.foldLeft(SubscriptionsStateStack())(_.withRegisteredPromise(_))
    // _ => Unit is because for all promises, request will be published immediately and it shouldn't trigger saving of (restored) state
    subscriptionStates = subscriptionsOffered.foldLeft(withRegistered.withNextState(_ => Unit))(_.withPublishedRequestFor(_))
    subscriptionsOffered.foreach(subscriptionManager.confirmOrRegister(_, self))
  }

  override private[rhttpc] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {
    subscriptionStates = subscriptionStates.withRegisteredPromise(sub)
  }

  override protected def onStateTransition(transitionData: TransitionData[S, D]): Unit = {
    subscriptionStates = subscriptionStates.withNextState { publishedRequests =>
      onFinishedJobAfterTransition(transitionData.toFinishedJobData(publishedRequests))
    }
  }

  override protected val handleSubscriptionMessages: Receive = {
    case RequestPublished(subscription) =>
      // TODO: timeouts for missing MessageFromSubscription
      subscriptionManager.confirmOrRegister(subscription, self)
      subscriptionStates = subscriptionStates.withPublishedRequestFor(subscription)
    case RequestAborted(subscription, cause) =>
      subscriptionStates = subscriptionStates.withAbortedRequestFor(subscription)
    case MessageFromSubscription(msg, subscription) =>
      subscriptionStates = subscriptionStates.withConsumedSubscription(subscription)
      self forward msg
  }

}