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

import akka.actor.{ActorRef, ActorLogging}

trait PersistentActorWithNotifications { this: PersistentActor with ActorLogging =>
  override def persistenceId: String = SnapshotsRegistry.persistenceId(persistenceCategory, id)

  protected def persistenceCategory: String

  protected def id: String

  private var listenersForSnapshotSave: Map[Long, RecipientWithMsg] = Map.empty

  override def receiveCommand: Receive = {
    case _ => throw new IllegalArgumentException("Should be used receive method")
  }

  protected def deleteSnapshotsLogging(): Unit = {
    deleteSnapshotsLogging(None)
  }

  private def deleteSnapshotsLogging(maxSequenceNr: Option[Long]): Unit = {
    log.debug(s"Deleting all snapshots for $persistenceId until: $maxSequenceNr...")
    deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr.getOrElse(Int.MaxValue)))
  }

  protected def saveSnapshotLogging(snapshot: Any): Unit = {
    saveSnapshotNotifying(snapshot, None)
  }

  protected def saveSnapshotNotifying(snapshot: Any, listener: Option[RecipientWithMsg]): Unit = {
    log.debug(s"Saving snapshot for $persistenceId: $snapshot ...")
    updateLastSequenceNr(lastSequenceNr + 1)
    listener.foreach { listener =>
      listenersForSnapshotSave += lastSequenceNr -> listener
    }
    saveSnapshot(snapshot)
  }

  protected val handleSnapshotEvents: Receive = {
    case SaveSnapshotSuccess(metadata) =>
      log.debug("State saved for " + persistenceId)
      deleteSnapshotsLogging(Some(lastSequenceNr-1))
      replyToListenerForSaveIfWaiting(metadata)
    case SaveSnapshotFailure(metadata, cause) =>
      val stringWriter = new StringWriter()
      val printWriter = new PrintWriter(stringWriter)
      cause.printStackTrace(printWriter)
      log.error(s"State save failure for $persistenceId.\nError: $stringWriter")
  }

  private def replyToListenerForSaveIfWaiting(metadata: SnapshotMetadata): Unit = {
    listenersForSnapshotSave.get(metadata.sequenceNr).foreach { listener =>
      listener.reply()
      listenersForSnapshotSave -= metadata.sequenceNr
    }
  }
}

class RecipientWithMsg(recipient: ActorRef, msg: Any) {
  def reply() = recipient ! msg
}

case object StateSaved