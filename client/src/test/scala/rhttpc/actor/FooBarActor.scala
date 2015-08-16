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
import rhttpc.client.{ReliableClient, SubscriptionManager}

import scala.concurrent.duration._
import scala.language.postfixOps

class FooBarActor(protected val id: String, client: ReliableClient[String]) extends MockReliableFSM[FooBarState, FooBarData] {
  import context.dispatcher

  override protected def persistenceCategory: String = FooBarActor.persistenceCategory

  override protected def subscriptionManager: SubscriptionManager = client.subscriptionManager

  startWith(InitState, EmptyData)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      client.send(msg) pipeTo this
      goto(WaitingForResponseState) replyingAfterSave()
  }
  
  when(WaitingForResponseState) {
    case Event("foo", _) => goto(FooState) acknowledgingAfterSave()
    case Event("bar", _) => goto(BarState) acknowledgingAfterSave()
  }

  when(FooState, stateTimeout = 5 minutes) {
    case Event(StateTimeout, _) => stop()
  }

  when(BarState, stateTimeout = 5 minutes) {
    case Event(StateTimeout, _) => stop()
  }

  whenUnhandled {
    case Event(CurrentState, _) =>
      sender() ! stateName
      stay()
    case Event(StopYourself, _) =>
      stop()
    case Event(event, _) if handleRecover.isDefinedAt(event) => // whenUnhandled doesn't append handlers so it must be duplicated
      handleRecover(event)
      stay()
  }
}

object FooBarActor {
  val persistenceCategory = "foobar"
  
  def props(id: String, client: ReliableClient[String]): Props = Props(new FooBarActor(id, client))
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