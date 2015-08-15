package rhttpc.client

import java.util.UUID

import akka.actor.{Actor, Props, ActorRef, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import org.scalatest._
import rhttpc.api.Correlated

import scala.concurrent.ExecutionContext

class SubscriptionManagerActorSpec
  extends TestKit(ActorSystem("SubscriptionManagerActorSpec"))
  with ImplicitSender
  with FlatSpecLike
  with Matchers {

  it should "ack after promise -> confirm -> reply -> consumed" in {
    val actor = system.actorOf(Props[SubscriptionManagerActor])
    val sub = SubscriptionOnResponse(UUID.randomUUID().toString)

    actor ! RegisterSubscriptionPromise(sub)

    val replyMock = TestProbe()
    actor ! ConfirmOrRegisterSubscription(sub, replyMock.ref)

    val ackProbe = TestProbe()
    ackProbe.send(actor, Correlated("foo", sub.correlationId))
    replyMock.expectMsg(MessageFromSubscription("foo", sub))

    ackProbe.expectNoMsg()
    replyMock.reply("ok")

    ackProbe.expectMsg("ok")
    ()
  }

  it should "ack after promise -> reply -> confirm -> consumed" in {
    val actor = system.actorOf(Props[SubscriptionManagerActor])
    val sub = SubscriptionOnResponse(UUID.randomUUID().toString)

    actor ! RegisterSubscriptionPromise(sub)

    val ackProbe = TestProbe()
    ackProbe.send(actor, Correlated("foo", sub.correlationId))

    val replyMock = TestProbe()
    actor ! ConfirmOrRegisterSubscription(sub, replyMock.ref)
    replyMock.expectMsg(MessageFromSubscription("foo", sub))

    ackProbe.expectNoMsg()
    replyMock.reply("ok")

    ackProbe.expectMsg("ok")
    ()
  }

}

