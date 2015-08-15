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
package rhttpc.actor

import rhttpc.client._

trait SubscriptionsHolder[S, D] extends PublicationListener with StateTransitionHandler[S, D] with RecoveryCompletedListener {
  
  protected def subscriptionManager: SubscriptionManager

  private var subscriptionStates: SubscriptionsStateStack = _

  private var optionalSubscriptionsOffered: Option[Set[SubscriptionOnResponse]] = None

  override protected def onSubscriptionsOffered(subs: Set[SubscriptionOnResponse]): Unit = {
    optionalSubscriptionsOffered = Some(subs)
  }

  override protected def onRecoveryCompleted(): Unit = {
    optionalSubscriptionsOffered match {
      case Some(subscriptionsOffered) =>
        // we won't save this state because it was offered already
        subscriptionStates = subscriptionsOffered.foldLeft(SubscriptionsStateStack())(_.withPublishedRequestFor(_)).withNextState(_ => Unit)
        subscriptionsOffered.foreach(subscriptionManager.confirmOrRegister(_, self))
      case None =>
        // initial start
        subscriptionStates = SubscriptionsStateStack()
    }
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
      subscriptionManager.confirmOrRegister(subscription, self)
      subscriptionStates = subscriptionStates.withPublishedRequestFor(subscription)
    case RequestAborted(subscription, cause) =>
      subscriptionStates = subscriptionStates.withAbortedRequestFor(subscription)
    case MessageFromSubscription(msg, subscription) =>
      subscriptionStates = subscriptionStates.withConsumedSubscription(subscription)
      self forward msg
  }

}