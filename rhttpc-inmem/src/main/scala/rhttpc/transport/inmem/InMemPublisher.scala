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

import akka.actor.{ActorRef, ActorSystem}
import rhttpc.transport.{DelayedMessage, Message, Publisher}

import scala.concurrent.Future

private[inmem] class InMemPublisher[Msg](queueActor: ActorRef)
                                        (implicit system: ActorSystem) extends Publisher[Msg] {

  import system.dispatcher

  override def publish(msg: Message[Msg]): Future[Unit] = {
    msg match {
      case delayed@DelayedMessage(_, delay, _, _) =>
        system.scheduler.scheduleOnce(delay, queueActor, delayed)
      case _ =>
        queueActor ! msg
    }
    Future.unit
  }

  override def start(): Unit = {}

  override def stop(): Future[Unit] =
    Future.unit

}