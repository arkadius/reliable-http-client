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

import akka.actor._

class RecoverableActorsManger(persistenceCategory: String, childPropsCreate: String => Props) extends Actor with ActorLogging {

  override def receive: Receive = {
    case RecoverAllActors =>
      val registry = context.actorOf(SnapshotsRegistry.props(persistenceCategory), "registry")
      registry ! GetIdsWithStoredSnapshots
      context.become(waitForIdsWithStoredSnapshots(registry, sender()))
  }

  private def waitForIdsWithStoredSnapshots(registry: ActorRef, originalSender: ActorRef): Receive = {
    case IdsWithStoredSnapshots(ids) =>
      if (ids.nonEmpty) {
        log.info(ids.mkString("Recovering actors from registry: ", ", ", ""))
        ids.foreach { id =>
          context.actorOf(childPropsCreate(id), id)
        }
      } else {
        log.info("Empty registry - nothing to recover")
      }
      originalSender ! ActorsRecovered
      registry ! PoisonPill
      context.become(handleFooBarMessages)
  }

  val handleFooBarMessages: Receive = {
    case SendMsgToChild(id, msg) =>
      context.child(id) match {
        case Some(child) => child forward msg
        case None =>
          val fooBar = context.actorOf(childPropsCreate(id), id)
          fooBar forward msg
      }
  }
}

object RecoverableActorsManger {
  def props(persistenceCategory: String, childPropsCreate: String => Props): Props =
    Props(new RecoverableActorsManger(persistenceCategory, childPropsCreate))
}

case object RecoverAllActors
case object ActorsRecovered

case class SendMsgToChild(id: String, msg: Any)