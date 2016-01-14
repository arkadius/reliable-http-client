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
package rhttpc.akkapersistence.impl

import akka.actor.FSM
import rhttpc.client.subscription.SubscriptionOnResponse

private[akkapersistence] trait StateTransitionHandler[S, D] {
  protected def onSubscriptionsOffered(subscriptions: Set[SubscriptionOnResponse]): Unit

  protected def onStateTransition(transitionData: TransitionData[S, D]): Unit

  protected def onFinishedJobAfterTransition(afterAllData: FinishedJobAfterTransitionData[S, D]): Unit
}

private[rhttpc] trait FSMStateTransitionRegistrar[S, D] { self: FSM[S, D] with StateTransitionHandler[S, D] with FSMAfterAllListenerHolder[S, D] =>

  protected def incOwnLastSequenceNr(): Long

  onTransition {
    case (_, to) =>
      onStateTransition(TransitionData[S, D](to, nextStateData, incOwnLastSequenceNr(), useCurrentAfterAllListener()))
  }

}

private[rhttpc] case class TransitionData[S, D](state: S, data: D, sequenceNumber: Long, afterAllListener: Option[RecipientWithMsg]) {
  def toFinishedJobData(subscriptions: Set[SubscriptionOnResponse]): FinishedJobAfterTransitionData[S, D] = {
    FinishedJobAfterTransitionData(state, data, subscriptions, sequenceNumber, afterAllListener)
  }
}

private[rhttpc] case class FinishedJobAfterTransitionData[S, D](state: S, data: D, subscriptions: Set[SubscriptionOnResponse], sequenceNumber: Long, afterAllListener: Option[RecipientWithMsg])