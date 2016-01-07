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

import rhttpc.client.SubscriptionOnResponse

case class SubscriptionsStateStack private (stack: List[SubscriptionsState]) {
  import SubscriptionsStateStack._

  def withNextState(onAllRequestsPublishedForPrevState: Set[SubscriptionOnResponse] => Unit) = {
    val head :: tail = stack
    val newTail = head.withOnAllRequestsPublished(onAllRequestsPublishedForPrevState) match {
      case AllSubscriptionsConsumed =>
        tail
      case SomeSubscriptionsLeft(updated) =>
        updated :: tail
    }
    copy(stack = SubscriptionsState() :: newTail)
  }

  def withRegisteredPromise(sub: SubscriptionOnResponse): SubscriptionsStateStack = {
    val head :: tail = stack
    copy(stack = head.withRegisteredPromise(sub) :: tail)
  }

  def withPublishedRequestFor(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    withCompletedSubscription(sub)(_.withPublishedRequestFor(sub))

  def withAbortedRequestFor(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    withCompletedSubscription(sub)(_.withAbortedRequestFor(sub))

  def withConsumedSubscription(sub: SubscriptionOnResponse): SubscriptionsStateStack =
    copy(stack = consumeSubscriptionOnStack(stack)(sub, _.withConsumedSubscription(sub)))

  private def withCompletedSubscription(sub: SubscriptionOnResponse)
                                       (complete: SubscriptionsState => SubscriptionsState): SubscriptionsStateStack = {
    copy(stack = completeSubscriptionOnStack(stack)(sub, complete))
  }
  
}

object SubscriptionsStateStack {
  def apply(): SubscriptionsStateStack = SubscriptionsStateStack(List(SubscriptionsState()))
  
  private def completeSubscriptionOnStack(s: List[SubscriptionsState])
                                         (sub: SubscriptionOnResponse,
                                          complete: SubscriptionsState => SubscriptionsState): List[SubscriptionsState] = s match {
    case head :: tail if head.containsSubscription(sub) => complete(head) :: tail
    case head :: tail => head :: completeSubscriptionOnStack(tail)(sub, complete)
    case Nil => Nil
  }

  private def consumeSubscriptionOnStack(s: List[SubscriptionsState])
                                        (sub: SubscriptionOnResponse,
                                         consume: SubscriptionsState => SubscriptionConsumptionResult): List[SubscriptionsState] = s match {
    case head :: tail if head.containsSubscription(sub) =>
      consume(head) match {
        case AllSubscriptionsConsumed =>
          tail
        case SomeSubscriptionsLeft(updated) =>
          updated :: tail
      }
    case head :: tail => head :: consumeSubscriptionOnStack(tail)(sub, consume)
    case Nil => Nil
  }
}

case class SubscriptionsState private (private val subscriptions: Map[SubscriptionOnResponse, SubscriptionState], onAllRequestsPublished: Set[SubscriptionOnResponse] => Unit) {
  def containsSubscription(sub: SubscriptionOnResponse) = subscriptions.contains(sub)

  def withRegisteredPromise(sub: SubscriptionOnResponse): SubscriptionsState = copy(subscriptions = subscriptions + (sub -> SubscriptionPromisedState))

  def withOnAllRequestsPublished(newOnAllRequestsPublished: Set[SubscriptionOnResponse] => Unit): SubscriptionConsumptionResult = {
    if (subscriptions.isEmpty) {
      newOnAllRequestsPublished(Set.empty)
      AllSubscriptionsConsumed
    } else {
      copy(onAllRequestsPublished = newOnAllRequestsPublished).consumptionResult
    }
  }
  
  def withPublishedRequestFor(sub: SubscriptionOnResponse): SubscriptionsState =
    copy(subscriptions = subscriptions.updated(sub, RequestPublishedState)).completionResult

  def withAbortedRequestFor(sub: SubscriptionOnResponse): SubscriptionsState =
    copy(subscriptions = subscriptions - sub).completionResult
  
  private def completionResult: SubscriptionsState = {
    val published = subscriptions.filter {
      case (k, v) => v.isPublished
    }
    if (published.size == subscriptions.size) {
      onAllRequestsPublished(published.keys.toSet)
    }
    this
  }

  def withConsumedSubscription(sub: SubscriptionOnResponse): SubscriptionConsumptionResult =
    copy(subscriptions = subscriptions.updated(sub, SubscriptionConsumedState)).consumptionResult
  
  private def consumptionResult: SubscriptionConsumptionResult = {
    if (subscriptions.values.forall(_ == SubscriptionConsumedState)) {
      AllSubscriptionsConsumed
    } else {
      SomeSubscriptionsLeft(this)
    }
  }
}

sealed trait SubscriptionState {
  def isPublished: Boolean
}

case object SubscriptionPromisedState extends SubscriptionState {
  override def isPublished: Boolean = false
}
case object RequestPublishedState extends SubscriptionState {
  override def isPublished: Boolean = true
}
case object SubscriptionConsumedState extends SubscriptionState {
  override def isPublished: Boolean = true
}

sealed trait SubscriptionConsumptionResult

case object AllSubscriptionsConsumed extends SubscriptionConsumptionResult

case class SomeSubscriptionsLeft(updated: SubscriptionsState) extends SubscriptionConsumptionResult

object SubscriptionsState {
  def apply(): SubscriptionsState =
    new SubscriptionsState(Map.empty, _ => throw new IllegalStateException("Callback should be executed after withNextState"))

  def apply(onAllRequestsPublished: Set[SubscriptionOnResponse] => Unit): SubscriptionsState =
    new SubscriptionsState(Map.empty, onAllRequestsPublished)
}