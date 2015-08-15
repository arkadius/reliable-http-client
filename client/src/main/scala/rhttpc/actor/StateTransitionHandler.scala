package rhttpc.actor

import akka.actor.Actor._
import akka.actor.FSM
import akka.persistence.RecipientWithMsg
import rhttpc.client.SubscriptionOnResponse

trait StateTransitionHandler[S, D] {

  protected def onStateTransition(transitionData: TransitionData[S, D]): Unit

  protected def onFinishedJobAfterTransition(afterAllData: FinishedJobAfterTransitionData[S, D]): Unit

  protected def onSubscriptionsOffered(subscriptions: Set[SubscriptionOnResponse]): Unit

  protected def handleSubscriptionMessages: Receive
}

trait FSMStateTransitionHandler[S, D] { self: FSM[S, D] with StateTransitionHandler[S, D] with FSMAfterAllListenerHolder[S, D] =>

  protected def incOwnLastSequenceNr(): Long

  onTransition {
    case (_, to) =>
      onStateTransition(TransitionData[S, D](to, nextStateData, incOwnLastSequenceNr(), useCurrentAfterAllListener()))
  }

}

case class TransitionData[S, D](state: S, data: D, sequenceNumber: Long, afterAllListener: Option[RecipientWithMsg]) {
  def toFinishedJobData(subscriptions: Set[SubscriptionOnResponse]): FinishedJobAfterTransitionData[S, D] = {
    FinishedJobAfterTransitionData(state, data, subscriptions, sequenceNumber, afterAllListener)
  }
}

case class FinishedJobAfterTransitionData[S, D](state: S, data: D, subscriptions: Set[SubscriptionOnResponse], sequenceNumber: Long, afterAllListener: Option[RecipientWithMsg])