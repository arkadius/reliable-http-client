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
package rhttpc.transport.inmem

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.routing.{RoundRobinRoutingLogic, Routee, Router}
import rhttpc.transport.Message

private class QueueActor extends Actor with Stash with ActorLogging {

  private var consumers = Map.empty[ActorRef, ActorRefRouteeWithSpecifiedMessageType]

  private var router = Router(RoundRobinRoutingLogic(), collection.immutable.IndexedSeq.empty)

  override def receive: Receive = {
    case RegisterConsumer(consumer, fullMessage) =>
      val routee = ActorRefRouteeWithSpecifiedMessageType(consumer, fullMessage)
      consumers += consumer -> routee
      router = router.addRoutee(routee)
      log.debug("Registered consumer, unstashing")
      unstashAll()
    case UnregisterConsumer(consumer) =>
      consumers.get(consumer).foreach { routee =>
        consumers -= consumer
        router = router.removeRoutee(routee)
      }
      sender() ! ((): Unit)
    case msg: Message[_] =>
      if (consumers.isEmpty) {
        log.debug("Got message when no consumer registered, stashing")
        stash()
        sender() ! ((): Unit)
      } else {
        router.route(msg, sender())
      }
  }

}

object QueueActor {
  def props: Props = Props(new QueueActor)
}

private[inmem] case class ActorRefRouteeWithSpecifiedMessageType(ref: ActorRef, fullMessage: Boolean) extends Routee {
  override def send(message: Any, sender: ActorRef): Unit = {
    if (fullMessage) {
      ref.tell(message, sender)
    } else {
      ref.tell(message.asInstanceOf[Message[_]].content, sender)
    }
  }
}

private[inmem] case class RegisterConsumer(consumer: ActorRef, fullMessage: Boolean)

private[inmem] case class UnregisterConsumer(consumer: ActorRef)