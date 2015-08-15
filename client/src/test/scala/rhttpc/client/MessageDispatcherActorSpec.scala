package rhttpc.client

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest._
import rhttpc.api.Correlated

class MessageDispatcherActorSpec
  extends TestKit(ActorSystem("MessageDispatcherActorSpec"))
  with ImplicitSender
  with FlatSpecLike
  with Matchers {

  it should "ack after promise -> confirm -> reply -> consumed" in {
    val actor = system.actorOf(Props[MessageDispatcherActor])
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
    val actor = system.actorOf(Props[MessageDispatcherActor])
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

