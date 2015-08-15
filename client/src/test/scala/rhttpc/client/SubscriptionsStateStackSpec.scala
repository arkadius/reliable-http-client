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

import org.scalatest._

class SubscriptionsStateStackSpec extends FlatSpec with Matchers {

  it should "notify about sub after register -> publish" in {
    var published: Set[SubscriptionOnResponse] = null
    val sub = SubscriptionOnResponse("foo")
    SubscriptionsStateStack(published = _).withRegisteredPromise(sub).withPublishedRequestFor(sub)
    published shouldEqual Set(sub)
  }

  it should "notify about sub after register -> next -> publish" in {
    var published1: Set[SubscriptionOnResponse] = null
    var published2: Set[SubscriptionOnResponse] = null
    val sub1 = SubscriptionOnResponse("foo1")
    SubscriptionsStateStack()
      .withRegisteredPromise(sub1)
      .withNextState(published1 = _)
      .withPublishedRequestFor(sub1)

    published1 shouldEqual Set(sub1)
  }

  it should "notify about sub after register1 -> register2 -> publish1 -> consume1 -> publish2" in {
    var published: Set[SubscriptionOnResponse] = null
    val sub1 = SubscriptionOnResponse("foo1")
    val sub2 = SubscriptionOnResponse("foo2")
    SubscriptionsStateStack(published = _)
      .withRegisteredPromise(sub1)
      .withRegisteredPromise(sub2)
      .withPublishedRequestFor(sub1)
      .withConsumedSubscription(sub1)
      .withPublishedRequestFor(sub2)

    published shouldEqual Set(sub1, sub2)
  }

  it should "notify about sub after register1 -> register2 -> publish1 -> abort2" in {
    var published: Set[SubscriptionOnResponse] = null
    val sub1 = SubscriptionOnResponse("foo1")
    val sub2 = SubscriptionOnResponse("foo2")
    SubscriptionsStateStack(published = _)
      .withRegisteredPromise(sub1)
      .withRegisteredPromise(sub2)
      .withPublishedRequestFor(sub1)
      .withAbortedRequestFor(sub2)

    published shouldEqual Set(sub1)
  }

  it should "notify about sub after empty -> next" in {
    var published1: Set[SubscriptionOnResponse] = null
    var published2: Set[SubscriptionOnResponse] = null
    val sub = SubscriptionOnResponse("foo")
    SubscriptionsStateStack()
      .withNextState(published1 = _)
      .withRegisteredPromise(sub)
      .withNextState(published2 = _)
      .withPublishedRequestFor(sub)

    published1 shouldEqual Set.empty
    published2 shouldEqual Set(sub)
  }

}