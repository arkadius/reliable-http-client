package rhttpc.client

import akka.actor.{Props, Status, Actor}

import scala.concurrent.Promise

private class PromiseSubscriptionCommandsListener(pubPromise: ReplyFuture, replyPromise: Promise[Any])
                                                 (request: Any, subscriptionManager: SubscriptionManager) extends SubscriptionCommandsListener {
  import context.dispatcher

  override private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {}

  override def receive: Actor.Receive = {
    case DoConfirmSubscription(sub) =>
      subscriptionManager.confirmOrRegister(sub, self)
      context.become(waitForMessage)
    case SubscriptionAborted(sub, cause) =>
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