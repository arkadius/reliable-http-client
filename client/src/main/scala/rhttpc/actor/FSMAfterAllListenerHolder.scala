package rhttpc.actor

import akka.actor.FSM
import akka.persistence.RecipientWithMsg

trait FSMAfterAllListenerHolder[S, D] { this: FSM[S, D] =>
  private var currentAfterAllListener: Option[RecipientWithMsg] = None

  implicit class StateExt(state: this.State) {
    def replyingAfterSave(msg: Any = StateSaved) = {
      currentAfterAllListener = Some(new RecipientWithMsg(sender(), msg))
      state
    }
  }

  protected def useCurrentAfterAllListener(): Option[RecipientWithMsg] = {
    val tmp = currentAfterAllListener
    currentAfterAllListener = None
    tmp
  }
}

case object StateSaved