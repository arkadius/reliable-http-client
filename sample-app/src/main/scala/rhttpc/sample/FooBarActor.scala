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
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.pattern._
import rhttpc.actor.ReliableFSM
import rhttpc.client.SubscriptionManager

import scala.concurrent.duration._
import scala.language.postfixOps

private class FooBarActor(protected val id: String, protected val subscriptionManager: SubscriptionManager, client: DelayedEchoClient) extends ReliableFSM[FooBarState, FooBarData] {
  import context.dispatcher

  override protected def persistenceCategory: String = FooBarActor.persistenceCategory

  startWith(InitState, EmptyData)

  when(InitState) {
    case Event(SendMsg(msg), _) =>
      client.requestResponse(msg) pipeTo this
      goto(WaitingForResponseState) replyingAfterSave()
  }
  
  when(WaitingForResponseState) {
    case Event(httpResponse: HttpResponse, _) =>
      self ! httpResponse.entity.asInstanceOf[HttpEntity.Strict].data.utf8String
      stay()
    case Event("foo", _) => goto(FooState)
    case Event("bar", _) => goto(BarState)
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
  }
}

object FooBarActor {
  val persistenceCategory = "foobar"
  
  def props(id: String, subscriptionManager: SubscriptionManager, client: DelayedEchoClient): Props = Props(new FooBarActor(id, subscriptionManager, client))
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