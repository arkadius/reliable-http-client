package rhttpc.client

import org.scalatest._

class SubscriptionsStateStackSpec extends FlatSpec with Matchers {

  it should "notify about sub after register -> publish" in {
    var published: Set[SubscriptionOnResponse] = null
    val sub = SubscriptionOnResponse("foo")
    SubscriptionsStateStack().withNextState(published = _).withRegisteredPromise(sub).withPublishedRequestFor(sub)
    published shouldEqual Set(sub)
  }

  it should "notify about sub after register -> next -> publish -> register -> publish" in {
    var published1: Set[SubscriptionOnResponse] = null
    var published2: Set[SubscriptionOnResponse] = null
    val sub1 = SubscriptionOnResponse("foo1")
    val sub2 = SubscriptionOnResponse("foo2")
    SubscriptionsStateStack()
      .withNextState(published1 = _)
      .withRegisteredPromise(sub1)
      .withNextState(published2 = _)
      .withPublishedRequestFor(sub1)
      .withRegisteredPromise(sub2)
      .withPublishedRequestFor(sub2)

    published1 shouldEqual Set(sub1)
    published2 shouldEqual Set(sub2)
  }

  it should "notify about sub after register1 -> register2 -> publish1 -> consume1 -> publish2" in {
    var published: Set[SubscriptionOnResponse] = null
    val sub1 = SubscriptionOnResponse("foo1")
    val sub2 = SubscriptionOnResponse("foo2")
    SubscriptionsStateStack()
      .withNextState(published = _)
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
    SubscriptionsStateStack()
      .withNextState(published = _)
      .withRegisteredPromise(sub1)
      .withRegisteredPromise(sub2)
      .withPublishedRequestFor(sub1)
      .withAbortedRequestFor(sub2)

    published shouldEqual Set(sub1)
  }

}
