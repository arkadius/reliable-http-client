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
package reliablehttpc

import akka.actor.FSM
import akka.actor.FSM._
import akka.persistence._

trait PersistedFSM[S, D] extends PersistentActor with FSM[S, D]{
  override def receiveRecover: Receive = {
    case SnapshotOffer(_, stateAndData: StateAndData[_, _]) =>
      val casted = stateAndData.asInstanceOf[StateAndData[S, D]]
      startWith(casted.state, casted.data)
  }

  override def receiveCommand: Receive = {
    case _ => throw new IllegalArgumentException("Should be used receive of FSM")
  }

  onTransition {
    case (_, to) =>
      deleteSnapshots(SnapshotSelectionCriteria())
      saveSnapshot(StateAndData(to, nextStateData))
  }

  onTermination {
    case StopEvent(Normal, _, _) => deleteSnapshots(SnapshotSelectionCriteria())
    case StopEvent(Failure(_), _, _) => deleteSnapshots(SnapshotSelectionCriteria())
  }

  override def receive: Receive = logSnapshotEvents orElse super.receive

  protected val logSnapshotEvents: Receive = {
    case _: SaveSnapshotSuccess =>
      log.debug("State saved for " + persistenceId)
      stay()
    case SaveSnapshotFailure(metadata, cause) =>
      log.error("State save failure for " + persistenceId, cause)
      stay()
  }
}

case class StateAndData[S, D](state: S, data: D)