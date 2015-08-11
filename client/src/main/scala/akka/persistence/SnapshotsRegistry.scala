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

import java.io.File

import akka.actor.{Props, Actor}

class SnapshotsRegistry(persistenceCategory: String) extends Actor {
  private val FilenamePattern = s"""^snapshot-$persistenceCategory-(.+)-(\\d+)-(\\d+)""".r

  private val config = context.system.settings.config.getConfig("akka.persistence.snapshot-store.local")
  private val snapshotDir = new File(config.getString("dir"))
  
  override def receive: Receive = {
    case GetIdsWithStoredSnapshots =>
      val ids = Option(snapshotDir.list()).toSeq.flatten.collect {
        case FilenamePattern(id, _, _) ⇒ id
      }.toSet
      sender() ! IdsWithStoredSnapshots(ids)
  }
  
}

object SnapshotsRegistry {
  def persistenceId(category: String, id: String) = s"$category-$id"

  def props(persistenceCategory: String): Props = Props(new SnapshotsRegistry(persistenceCategory))
}

case object GetIdsWithStoredSnapshots

case class IdsWithStoredSnapshots(ids: Set[String])