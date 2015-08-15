package rhttpc.actor

import akka.actor.{Actor, Props, Status}
import rhttpc.client._

import scala.concurrent.Promise

private class PromiseSubscriptionCommandsListener(pubPromise: ReplyFuture, replyPromise: Promise[Any])
                                                 (request: Any, subscriptionManager: SubscriptionManager) extends PublicationListener {
  import context.dispatcher

  override private[rhttpc] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {}

  override def receive: Actor.Receive = {
    case RequestPublished(sub) =>
      subscriptionManager.confirmOrRegister(sub, self)
      context.become(waitForMessage)
    case RequestAborted(sub, cause) =>
      replyPromise.failure(new NoAckException(request, cause))
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

object PromiseSubscriptionCommandsListener {
  def props(pubPromise: ReplyFuture, replyPromise: Promise[Any])
           (request: Any, subscriptionManager: SubscriptionManager): Props =
    Props(new PromiseSubscriptionCommandsListener(pubPromise, replyPromise)(request, subscriptionManager))
}

class NoAckException(request: Any, cause: Throwable) extends Exception(s"No acknowledge for request: $request", cause)