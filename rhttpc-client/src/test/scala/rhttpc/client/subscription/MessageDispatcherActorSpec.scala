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
package rhttpc.client.subscription

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpecLike
import rhttpc.client.protocol.{Correlated, SuccessExchange}

class MessageDispatcherActorSpec
  extends TestKit(ActorSystem("MessageDispatcherActorSpec"))
  with ImplicitSender
  with AnyFlatSpecLike
  with Matchers {

  it should "ack after promise -> confirm -> reply -> consumed" in {
    val actor = system.actorOf(Props[MessageDispatcherActor])
    val sub = SubscriptionOnResponse(UUID.randomUUID().toString)

    actor ! RegisterSubscriptionPromise(sub)

    val replyMock = TestProbe()
    actor ! ConfirmOrRegisterSubscription(sub, replyMock.ref)

    val ackProbe = TestProbe()
    ackProbe.send(actor, Correlated(SuccessExchange("fooReq", "foo"), sub.correlationId))
    replyMock.expectMsg(MessageFromSubscription("foo", sub))

    ackProbe.expectNoMessage()
    replyMock.reply("ok")

    ackProbe.expectMsg("ok")
    ()
  }

  it should "ack after promise -> reply -> confirm -> consumed" in {
    val actor = system.actorOf(Props[MessageDispatcherActor])
    val sub = SubscriptionOnResponse(UUID.randomUUID().toString)

    actor ! RegisterSubscriptionPromise(sub)

    val ackProbe = TestProbe()
    ackProbe.send(actor, Correlated(SuccessExchange("fooReq", "foo"), sub.correlationId))

    val replyMock = TestProbe()
    actor ! ConfirmOrRegisterSubscription(sub, replyMock.ref)
    replyMock.expectMsg(MessageFromSubscription("foo", sub))

    ackProbe.expectNoMessage()
    replyMock.reply("ok")

    ackProbe.expectMsg("ok")
    ()
  }

}