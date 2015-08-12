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
package rhttpc.sample

import akka.actor._
import akka.persistence.{IdsWithStoredSnapshots, GetIdsWithStoredSnapshots, SnapshotsRegistry}
import rhttpc.client.SubscriptionManager

class FooBarsManger(fooBarPropsCreate: String => Props) extends Actor with ActorLogging {

  override def receive: Receive = {
    case RecoverAllFooBars =>
      val registry = context.actorOf(SnapshotsRegistry.props(FooBarActor.persistenceCategory), "registry")
      registry ! GetIdsWithStoredSnapshots
      context.become(waitForIdsWithStoredSnapshots(registry, sender()))
  }

  private def waitForIdsWithStoredSnapshots(registry: ActorRef, originalSender: ActorRef): Receive = {
    case IdsWithStoredSnapshots(ids) =>
      if (ids.nonEmpty) {
        log.info(ids.mkString("Recovering actors from registry: ", ", ", ""))
        ids.foreach { id =>
          context.actorOf(fooBarPropsCreate(id), id)
        }
      } else {
        log.info("Empty registry - nothing to recover")
      }
      originalSender ! FooBarsRecovered
      registry ! PoisonPill
      context.become(handleFooBarMessages)
  }

  val handleFooBarMessages: Receive = {
    case SendMsgToFooBar(id, msg) =>
      context.child(id) match {
        case Some(fooBar) => fooBar forward msg
        case None =>
          val fooBar = context.actorOf(fooBarPropsCreate(id), id)
          fooBar forward msg
      }
  }
}

object FooBarsManger {
  def props(subscriptionManager: SubscriptionManager, client: DelayedEchoClient): Props =
    Props(new FooBarsManger(id => FooBarActor.props(id, subscriptionManager, client)))
}

case object RecoverAllFooBars
case object FooBarsRecovered

case class SendMsgToFooBar(id: String, msg: Any)