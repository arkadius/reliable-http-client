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

import akka.pattern._
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.routing.{RoundRobinRoutingLogic, Routee, Router}
import akka.util.Timeout
import rhttpc.transport.{Message, RejectingMessage}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

private class QueueActor(consumeTimeout: FiniteDuration,
                         retryDelay: FiniteDuration) extends Actor with Stash with ActorLogging {

  import context.dispatcher

  private var consumers = Map.empty[ActorRef, AskingActorRefRouteeWithSpecifiedMessageType]

  private var router = Router(RoundRobinRoutingLogic(), collection.immutable.IndexedSeq.empty)

  override def receive: Receive = {
    case RegisterConsumer(consumer, fullMessage) =>
      val routee = AskingActorRefRouteeWithSpecifiedMessageType(consumer, consumeTimeout, handleResponse, fullMessage)
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
        implicit val timeout = Timeout(consumeTimeout)
        sender() ! ((): Unit)
      } else {
        router.route(msg, sender())
      }
  }

  private def handleResponse(future: Future[Any], msg: Message[_]): Unit =
    future.recover {
      case ex: AskTimeoutException =>
        log.error(ex, s"REJECT [${msg.content.getClass.getName}] because of ask timeout")
      case ex: Exception with RejectingMessage =>
        log.error(ex, s"REJECT [${msg.content.getClass.getName}] because of rejecting failure")
      case NonFatal(ex) =>
        log.error(ex, s"Will RETRY [${msg.content.getClass.getName}] after $retryDelay because of failure")
        context.system.scheduler.scheduleOnce(retryDelay, self, msg)
    }

}

object QueueActor {
  def props(consumeTimeout: FiniteDuration,
            retryDelay: FiniteDuration): Props = Props(
    new QueueActor(
      consumeTimeout = consumeTimeout,
      retryDelay = retryDelay))
}

private[inmem] case class AskingActorRefRouteeWithSpecifiedMessageType(ref: ActorRef,
                                                                       askTimeout: FiniteDuration,
                                                                       handleResponse: (Future[Any], Message[_]) => Unit,
                                                                       fullMessage: Boolean)
  extends Routee {

  override def send(message: Any, sender: ActorRef): Unit = {
    val typedMessage = message.asInstanceOf[Message[_]]
    val msgToSend = if (fullMessage) message else typedMessage.content
    handleResponse(ref.ask(msgToSend)(askTimeout, sender), typedMessage)
  }
}

private[inmem] case class RegisterConsumer(consumer: ActorRef, fullMessage: Boolean)

private[inmem] case class UnregisterConsumer(consumer: ActorRef)