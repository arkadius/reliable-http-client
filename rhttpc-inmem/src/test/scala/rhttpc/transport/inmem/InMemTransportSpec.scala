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

import akka.actor.{ActorSystem, Status}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest._
import rhttpc.transport.PubSubTransport

import scala.concurrent.Await
import scala.concurrent.duration._

class InMemTransportSpec extends TestKit(ActorSystem("InMemTransportSpec"))
  with fixture.FlatSpecLike
  with BeforeAndAfterAll {

  import rhttpc.transport.dumb._

  val someQueueName = "fooQueue"
  val someMessage = "fooMessage"
  val someMessage2 = "fooMessage2"

  it should "delivery message to consumer subscribed before publishing" in { transport =>
    val probe = TestProbe()
    val subscriber = transport.subscriber[String](someQueueName, probe.testActor)
    subscriber.start()
    val publisher = transport.publisher[String](someQueueName)
    publisher.publish(someMessage)
    probe.expectMsg(someMessage)
    probe.reply(Unit)
  }

  it should "delivery message to consumer subscribed after publishing" in { transport =>
    val probe = TestProbe()
    val publisher = transport.publisher[String](someQueueName)
    val subscriber = transport.subscriber[String](someQueueName, probe.testActor)
    subscriber.start()
    publisher.publish(someMessage)
    probe.expectMsg(someMessage)
    probe.reply(Unit)
  }

  it should "delivery message to consumer started after publishing" in { transport =>
    val probe = TestProbe()
    val publisher = transport.publisher[String](someQueueName)
    val subscriber = transport.subscriber[String](someQueueName, probe.testActor)
    publisher.publish(someMessage)
    subscriber.start()
    probe.expectMsg(someMessage)
    probe.reply(Unit)
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
    probe1.reply(Unit)
    probe2.expectMsg(someMessage2)
    probe2.reply(Unit)
  }

  it should "retry message if failure" in { transport =>
    val probe = TestProbe()
    val subscriber = transport.subscriber[String](someQueueName, probe.testActor)
    subscriber.start()
    val publisher = transport.publisher[String](someQueueName)
    publisher.publish(someMessage)
    probe.expectMsg(someMessage)
    probe.reply(Status.Failure(new Exception("failure")))
    probe.expectMsg(someMessage)
    probe.reply(Unit)
  }

  override type FixtureParam = PubSubTransport

  override protected def withFixture(test: OneArgTest): Outcome = {
    val transport = InMemTransport(retryDelay = 0.seconds)
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