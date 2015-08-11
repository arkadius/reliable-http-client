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
package akka.persistence

import java.io.{PrintWriter, StringWriter}

import akka.actor.FSM._
import akka.actor._
import reliablehttpc._

trait PersistedFSM[S, D] extends PersistentActor with PersistentActorWithNotifications with FSM[S, D] with SubscriptionsHolder {
  private var replyAfterSaveMsg: Option[Any] = None

  implicit class StateExt(state: State) {
    def replyingAfterSave(msg: Any = StateSaved): PersistedFSM.this.State = {
      replyAfterSaveMsg = Some(msg)
      state
    }
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, stateAndData) =>
      log.info(s"Recovering: $persistenceId from snapshot: $stateAndData")
      val casted = stateAndData.asInstanceOf[FSMState[S, D]]
      startWith(casted.state, casted.data)
  }

  onTransition {
    case (_, to) =>
      saveSnapshotNotifying(FSMState(to, nextStateData, subscriptions))
  }

  onTermination {
    case StopEvent(Normal, _, _) =>
      deleteSnapshotsLogging()
    case StopEvent(Failure(_), _, _) =>
      deleteSnapshotsLogging()
  }


  protected def needToAddListener(): Option[RecipientWithMsg] = {
    replyAfterSaveMsg.map { msg =>
      replyAfterSaveMsg = None
      new RecipientWithMsg(sender(), msg)
    }
  }

  override def receive: Receive =
    handleSnapshotEvents orElse
      handleMessageFromSubscription orElse
      super.receive

}

case class FSMState[S, D](state: S, data: D, subscriptions: Set[Subscription])