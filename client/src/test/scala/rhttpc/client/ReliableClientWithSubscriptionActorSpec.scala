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
package rhttpc.client

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestActors.EchoActor
import akka.testkit._
import org.scalatest.Matchers

import scala.concurrent.ExecutionContext

class ReliableClientWithSubscriptionActorSpec
  extends TestKit(ActorSystem("ReliableClientWithSubscriptionActorSpec"))
  with ReliableClientBaseSpec
  with ImplicitSender
  with Matchers {

  it should "got success reply" in { fixture =>
    val replyMock = TestProbe()
    val actor = system.actorOf(MockSubscriptionActor.props(fixture.client, replyMock.ref))
    actor ! SendRequest
    fixture.transport.publicationPromise.success(Unit)
    expectMsg(Unit)

    fixture.transport.replySubscriptionPromise.success("bar")
    replyMock.expectMsg("bar")
  }

  it should "got subscription aborted" in { fixture =>
    val replyMock = TestProbe()
    val actor = system.actorOf(MockSubscriptionActor.props(fixture.client, replyMock.ref))
    actor ! SendRequest
    fixture.transport.publicationPromise.failure(FailedAcknowledge)
    expectMsgAllClassOf(classOf[SubscriptionAborted])
  }

}

class MockSubscriptionActor(client: ReliableClient[String], replyMock: ActorRef)(implicit ec: ExecutionContext) extends SubscriptionPromiseRegistrationListener {
  override def receive: Receive = {
    case SendRequest =>
      client.send("foo") pipeTo this
  }

  override private[client] def subscriptionPromiseRegistered(sub: SubscriptionOnResponse): Unit = {
    context.become(waitingOnSubscriptionCommand(sender()))
  }

  private def waitingOnSubscriptionCommand(originalSender: ActorRef): Receive = {
    case DoConfirmSubscription(sub) =>
      client.subscriptionManager.confirmOrRegister(sub, self)
      originalSender ! Unit
      context.become(waitingOnReply)
    case a: SubscriptionAborted =>
      originalSender ! a
      context.stop(self)
  }
  
  private def waitingOnReply: Receive = {
    case MessageFromSubscription(reply, sub) =>
      replyMock ! reply
      context.stop(self)
  }
}

object MockSubscriptionActor {
  def props(client: ReliableClient[String], replyMock: ActorRef)(implicit ec: ExecutionContext): Props = Props(new MockSubscriptionActor(client, replyMock))
}

case object SendRequest