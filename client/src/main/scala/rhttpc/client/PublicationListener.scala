package rhttpc.client

import akka.actor.Actor

trait PublicationListener extends Actor {
  private[rhttpc] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit
}

sealed trait PublicationResult

case class RequestPublished(subscription: SubscriptionOnResponse) extends PublicationResult

case class RequestAborted(subscription: SubscriptionOnResponse, cause: Throwable) extends PublicationResult

case class MessageFromSubscription(msg: Any, subscription: SubscriptionOnResponse)