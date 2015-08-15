package rhttpc.actor

import rhttpc.client._

trait SubscriptionsHolder[S, D] extends PublicationListener with StateTransitionHandler[S, D] {
  
  protected def subscriptionManager: SubscriptionManager

  private var subscriptionStates: SubscriptionsStateStack = SubscriptionsStateStack()

  override protected def onSubscriptionsOffered(subs: Set[SubscriptionOnResponse]): Unit = {
    subscriptionStates = subs.foldLeft(subscriptionStates)(_.withPublishedRequestFor(_))
    subs.foreach(subscriptionManager.confirmOrRegister(_, self))
  }

  override private[rhttpc] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {
    subscriptionStates = subscriptionStates.withRegisteredPromise(sub)
  }

  override protected def onStateTransition(afterAllListener: TransitionData[S, D]): Unit = {
    subscriptionStates = subscriptionStates.withNextState { publishedRequests =>
      onFinishedJobAfterTransition(afterAllListener.toFinishedJobData(publishedRequests))
    }
  }

  override protected def handleSubscriptionMessages: Receive = {
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