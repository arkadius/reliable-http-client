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
package rhttpc.actor

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import rhttpc.actor.impl.{GetIdsWithStoredSnapshots, IdsWithStoredSnapshots, NotifyAboutRecoveryCompleted, SnapshotsRegistry}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

private class RecoverableActorsManager(persistenceCategory: String, childPropsCreate: String => Props) extends Actor with ActorLogging {

  import context.dispatcher

  override def receive: Receive = {
    case RecoverAllActors =>
      val registry = context.actorOf(SnapshotsRegistry.props(persistenceCategory), "registry")
      registry ! GetIdsWithStoredSnapshots
      context.become(waitForIdsWithStoredSnapshots(registry, sender()))
  }

  private def waitForIdsWithStoredSnapshots(registry: ActorRef, originalSender: ActorRef): Receive = {
    case IdsWithStoredSnapshots(ids) =>
      val recoveryFinishedFuture =
        if (ids.nonEmpty) {
          log.info(ids.mkString("Recovering actors from registry: ", ", ", ""))
          implicit val timeout = Timeout(1 minute)
          val futures = ids.map { id =>
            context.actorOf(childPropsCreate(id), id) ? NotifyAboutRecoveryCompleted
          }
          Future.sequence(futures).map { _ =>
            log.info("Recovering of all actors completed")
          }
        } else {
          log.info("Empty registry - nothing to recover")
          Future.successful(Unit)
        }
      recoveryFinishedFuture.foreach { _ =>
        originalSender ! ActorsRecovered
        registry ! PoisonPill
        self ! BecomeRecovered
      }
    case BecomeRecovered =>
      context.become(recovered)
  }

  val recovered: Receive = {
    case SendMsgToChild(id, msg) =>
      context.child(id) match {
        case Some(child) => child forward msg
        case None =>
          val child = context.actorOf(childPropsCreate(id), id)
          child forward msg
      }
  }

}

case object BecomeRecovered

object RecoverableActorsManager {
  def props(persistenceCategory: String, childPropsCreate: String => Props): Props =
    Props(new RecoverableActorsManager(persistenceCategory, childPropsCreate))
}

case object RecoverAllActors
case object ActorsRecovered

case class SendMsgToChild(id: String, msg: Any)