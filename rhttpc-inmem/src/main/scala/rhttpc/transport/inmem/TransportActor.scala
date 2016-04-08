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

import akka.actor.{Actor, Props, Status}

import scala.util.control.NonFatal

private class TransportActor(queueActorProps: => Props) extends Actor {

  override def receive: Receive = {
    case GetOrCreateQueue(name) =>
      try {
        val ref = context.child(name).getOrElse(context.actorOf(queueActorProps, name))
        sender() ! ref
      } catch {
        case NonFatal(ex) =>
          sender() ! Status.Failure(ex)
      }
  }

}

object TransportActor {
  def props(queueActorProps: => Props): Props =
    Props(new TransportActor(queueActorProps))
}

private[inmem] case class GetOrCreateQueue(name: String)