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
package reliablehttpc.sample

import akka.actor.{Actor, Props}

class FooBarsManger(fooBarPropsCreate: String => Props) extends Actor {
  override def receive: Receive = {
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
  def props(client: DelayedEchoClient): Props = Props(new FooBarsManger(id => FooBarActor.props(id, client)))
}

case class SendMsgToFooBar(id: String, msg: Any)