package rhttpc.transport.inmem

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import org.scalatest._
import rhttpc.transport.PubSubTransport

import scala.concurrent.Await

class InMemTransportSpec extends TestKit(ActorSystem("InMemTransportSpec"))
  with fixture.FlatSpecLike
  with BeforeAndAfterAll {

  val someQueueName = "fooQueue"
  val someMessage = "fooMessage"
  val someMessage2 = "fooMessage2"

  it should "delivery message to consumer subscribed before publishing" in { transport =>
    val subscriber = transport.subscriber[String](someQueueName, testActor)
    subscriber.start()
    val publisher = transport.publisher[String](someQueueName)
    publisher.publish(someMessage)
    expectMsg(someMessage)
  }

  it should "delivery message to consumer subscribed after publishing" in { transport =>
    val publisher = transport.publisher[String](someQueueName)
    val subscriber = transport.subscriber[String](someQueueName, testActor)
    subscriber.start()
    publisher.publish(someMessage)
    expectMsg(someMessage)
  }

  it should "delivery message to consumer started after publishing" in { transport =>
    val publisher = transport.publisher[String](someQueueName)
    val subscriber = transport.subscriber[String](someQueueName, testActor)
    publisher.publish(someMessage)
    subscriber.start()
    expectMsg(someMessage)
  }

  it should "delivery message to multiple consumers" in { transport =>
    val probe1 = TestProbe()
    val subscriber = transport.subscriber[String](someQueueName, probe1.testActor)
    subscriber.start()

    val probe2 = TestProbe()
    val subscriber2 = transport.subscriber[String](someQueueName, probe2.testActor)
    subscriber2.start()

    val publisher = transport.publisher[String](someQueueName)
    publisher.publish(someMessage)
    publisher.publish(someMessage2)

    probe1.expectMsg(someMessage)
    probe2.expectMsg(someMessage2)
  }

  override type FixtureParam = PubSubTransport

  override protected def withFixture(test: OneArgTest): Outcome = {
    val transport = InMemTransport()
    try {
      test(transport)
    } finally {
      Await.result(transport.stop(), InMemDefaults.stopTimeout)
    }
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
