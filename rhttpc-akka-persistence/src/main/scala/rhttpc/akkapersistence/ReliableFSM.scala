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
package rhttpc.akkapersistence

import akka.actor.FSM._
import akka.actor._
import akka.persistence.{AkkaPersistentSnapshotter, SnapshotOffer}
import rhttpc.akkapersistence.impl._
import rhttpc.client.subscription.SubscriptionOnResponse

trait ReliableFSM[S, D] extends AkkaPersistentSnapshotter with AbstractReliableFSM[S, D]

trait AbstractReliableFSM[S, D]
  extends PersistentFSM[S, D]
  with SubscriptionsHolder[S, D] { self: AbstractSnapshotter =>

  override def receive: Receive =
    handleNotifyAboutRecoveryCompleted orElse
      handleSubscriptionMessages orElse
      handleSnapshotEvents orElse
      super.receive
}

trait PersistentFSM[S, D]
  extends PersistentActorWithNotifications
  with FSM[S, D]
  with FSMAfterAllListenerHolder[S, D]
  with FSMStateTransitionRegistrar[S, D]
  with NotifierAboutRecoveryCompleted { self: AbstractSnapshotter with StateTransitionHandler[S, D]  =>

  private var ownLastSequenceNr = 0L
  
  override protected def incOwnLastSequenceNr(): Long = {
    ownLastSequenceNr += 1
    ownLastSequenceNr
  }

  override def receiveRecover: Receive =
    handleSnapshotOffer orElse
      handleRecoveryCompleted

  private val handleSnapshotOffer: Receive = {
    case SnapshotOffer(metadata, snapshot) =>
      log.info(s"Recovering: $metadata from snapshot: $snapshot")
      ownLastSequenceNr = metadata.sequenceNr
      val casted = snapshot.asInstanceOf[FSMState[S, D]]
      onSubscriptionsOffered(casted.subscriptions)
      startWith(casted.state, casted.data)
  }

  override protected def onFinishedJobAfterTransition(afterAllData: FinishedJobAfterTransitionData[S, D]): Unit = {
    saveSnapshotNotifying(FSMState(afterAllData), afterAllData.sequenceNumber, afterAllData.afterAllListener)
  }

  onTermination {
    case StopEvent(Normal, _, _) =>
      deleteSnapshotsLogging()
    case StopEvent(Failure(_), _, _) =>
      deleteSnapshotsLogging()
  }

}

case class FSMState[S, D](state: S, data: D, subscriptions: Set[SubscriptionOnResponse])

object FSMState {
  def apply[S, D](data: FinishedJobAfterTransitionData[S, D]): FSMState[S, D] = FSMState(data.state, data.data, data.subscriptions)
}

case object StateSaved