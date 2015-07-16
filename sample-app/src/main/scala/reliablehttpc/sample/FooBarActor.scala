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

import akka.actor._
import akka.pattern._
import reliablehttpc.PersistedFSM
import concurrent.duration._
import scala.language.postfixOps

class FooBarActor(id: String, client: DelayedEchoClient) extends PersistedFSM[FooBarState, FooBarData] {
  override def persistenceId: String = "foobar-" + id

  import context.dispatcher

  startWith(InitState, EmptyData)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      client.requestResponse(msg) pipeTo self
      goto(WaitingForResponseState)
  }
  
  when(WaitingForResponseState) {
    case Event("foo", _) => goto(FooState)
    case Event("bar", _) => goto(BarState)
  }

  when(FooState, stateTimeout = 10 seconds) {
    case Event(StateTimeout, _) => stop()
  }

  when(BarState, stateTimeout = 10 seconds) {
    case Event(StateTimeout, _) => stop()
  }

  whenUnhandled {
    case Event(CurrentState, _) =>
      sender() ! stateName
      stay()
    case Event(StopYourself, _) =>
      stop()
  }
}

sealed trait FooBarState

case object InitState extends FooBarState
case object WaitingForResponseState extends FooBarState
case object FooState extends FooBarState
case object BarState extends FooBarState

sealed trait FooBarData

case object EmptyData extends FooBarData

case class SendMsg(msg: String)
case object CurrentState
case object StopYourself