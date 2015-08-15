package rhttpc.client

import akka.actor._

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait SubscriptionsHolder extends SubscriptionCommandsListener {
  
  private implicit def ec: ExecutionContext = context.dispatcher

  protected def subscriptionManager: SubscriptionManager

  protected var subscriptionPromises: Set[SubscriptionOnResponse] = Set.empty
  
  protected var subscriptions: Set[SubscriptionOnResponse] = Set.empty

  protected def registerSubscriptions(subs: Set[SubscriptionOnResponse]): Future[Set[Unit]] = {
    subscriptions ++= subs
    Future.sequence(subscriptions.map(subscriptionManager.confirmOrRegister(_, self)))
  }

  override private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {
    subscriptionPromises += sub
  }

  protected val handleSubscriptionMessages: Receive = {
    case DoConfirmSubscription(subscription) =>
      subscriptions = subscriptions + subscription
      removeSubscriptionPromise(subscription, () => Unit)
      stateChanged()
      subscriptionManager.confirmOrRegister(subscription, self)
    case SubscriptionAborted(subscription, cause) =>
      removeSubscriptionPromise(subscription, () => Unit)
      stateChanged()
//      subscriptionManager.(subscription, self)
    case MessageFromSubscription(msg, subscription) =>
      subscriptions = subscriptions - subscription
      stateChanged()
      self forward msg
  }

  private def removeSubscriptionPromise(sub: SubscriptionOnResponse, onNoPromisesLeft: () => Unit) = {
    subscriptionPromises -= sub
    if (subscriptionPromises.isEmpty)
      onNoPromisesLeft()
  }

  def stateChanged(): Unit
}

trait SubscriptionCommandsListener extends Actor {
  private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit
}

sealed trait SubscriptionCommand

case class DoConfirmSubscription(subscription: SubscriptionOnResponse) extends SubscriptionCommand

case class SubscriptionAborted(subscription: SubscriptionOnResponse, cause: Throwable) extends SubscriptionCommand

case class MessageFromSubscription(msg: Any, subscription: SubscriptionOnResponse)