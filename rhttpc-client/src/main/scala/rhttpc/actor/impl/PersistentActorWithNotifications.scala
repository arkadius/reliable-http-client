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
package rhttpc.actor.impl

import java.io.{PrintWriter, StringWriter}

import akka.actor.{ActorLogging, ActorRef}
import akka.persistence._

private[rhttpc] trait PersistentActorWithNotifications { this: AbstractSnapshotter with ActorLogging =>
  override def persistenceId: String = SnapshotsRegistry.persistenceId(persistenceCategory, id)

  protected def persistenceCategory: String

  protected def id: String

  private var listenersForSnapshotSave: Map[Long, RecipientWithMsg] = Map.empty

  protected def deleteSnapshotsLogging(): Unit = {
    deleteSnapshotsLogging(None)
  }

  private def deleteSnapshotsLogging(maxSequenceNr: Option[Long]): Unit = {
    log.debug(s"Deleting all snapshots for $persistenceId until (inclusive): $maxSequenceNr...")
    deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNr.getOrElse(Int.MaxValue)))
  }

  protected def saveSnapshotNotifying(snapshot: Any, sequenceNr: Long, listener: Option[RecipientWithMsg]): Unit = {
    log.debug(s"Saving snapshot for $persistenceId with sequenceNr: $sequenceNr: $snapshot ...")
    listener.foreach { listener =>
      listenersForSnapshotSave += sequenceNr -> listener
    }
    saveSnapshotWithSeqNr(snapshot, sequenceNr)
  }

  protected val handleSnapshotEvents: Receive = {
    case SaveSnapshotSuccess(metadata) =>
      log.debug(s"State saved for: $metadata")
      deleteSnapshotsLogging(Some(metadata.sequenceNr-1))
      replyToListenerForSaveIfWaiting(metadata)
    case DeleteSnapshotsSuccess(criteria) =>
      log.debug(s"Snapshots with criteria: $criteria deleted")
    case SaveSnapshotFailure(metadata, cause) =>
      log.error(cause, s"State save failure for: $metadata")
    case DeleteSnapshotsFailure(criteria, cause) =>
      log.warning(s"Delete snapshots with criteria failure: $criteria.\nError: ${printStackTrace(cause)}")
  }

  private def printStackTrace(cause: Throwable): String = {
    val stringWriter = new StringWriter()
    val printWriter = new PrintWriter(stringWriter)
    cause.printStackTrace(printWriter)
    stringWriter.toString
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