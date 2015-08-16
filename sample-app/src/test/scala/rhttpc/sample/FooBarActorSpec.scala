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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.testkit._
import org.scalatest._
import rhttpc.actor._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.io.Directory

class FooBarActorSpec extends TestKit(ActorSystem()) with ImplicitSender with FlatSpecLike with BeforeAndAfterAll {
  import system.dispatcher

  it should "be in init state initially" in withNextFooBar { fooBarActor =>
    fooBarActor ! CurrentState
    expectMsg(InitState)
  }

  it should "go into waiting state after send msg command" in withNextFooBar { fooBarActor =>
    fooBarActor ! SendMsg("foo")
    fooBarActor ! CurrentState
    expectMsg(WaitingForResponseState)
  }

  it should "go into foo state after echo response" in withNextFooBar { fooBarActor =>
    TestProbe().send(fooBarActor, SendMsg("foo"))
    val scheduled = system.scheduler.schedule(0.millis, 100.millis, fooBarActor, CurrentState)
    fishForMessage(10 seconds) {
      case WaitingForResponseState => false
      case FooState => true
    }
    scheduled.cancel()
  }

  it should "restore persited waiting state and continue with response" in {
    val id = "persisted"
    val fooBarActor = createFooBar(id)
    fooBarActor ! SendMsg("foo")
    expectMsg(10 seconds, StateSaved)

    val probe = TestProbe()
    probe watch fooBarActor
    fooBarActor ! PoisonPill
    probe.expectTerminated(fooBarActor)

    val restoredActor = createFooBar(id)
    restoredActor ! CurrentState
    expectMsg(WaitingForResponseState)
    restoredActor ! "foo"
    restoredActor ! CurrentState
    expectMsg(FooState)
    gracefulStop(restoredActor)
  }

  it should "restore persisted actors just after manager init" in {
    val mgr = createFooBarManager()

    val id = "saved"
    mgr ! SendMsgToChild(id, SendMsg("foo"))
    expectMsg(10 seconds, StateSaved)

    val probe = TestProbe()
    probe watch mgr
    mgr ! PoisonPill
    probe.expectTerminated(mgr)

    val restoredMgr = createFooBarManager()
    restoredMgr ! SendMsgToChild(id, CurrentState)
    expectMsg(WaitingForResponseState)
    restoredMgr ! SendMsgToChild(id, "foo")
    restoredMgr ! SendMsgToChild(id, CurrentState)
    expectMsg(FooState)
    ()
  }

  val id = new AtomicInteger(0)

  def withNextFooBar(test: ActorRef => Unit) = {
    val fooBarACtor = createFooBar(id.getAndIncrement().toString)
    test(fooBarACtor)
    gracefulStop(fooBarACtor)
  }

  def gracefulStop(fooBarActor: ActorRef) {
    val probe = TestProbe()
    probe watch fooBarActor
    fooBarActor ! StopYourself
    probe.expectTerminated(fooBarActor)
  }

  def createFooBar(id: String): ActorRef = {
    val client = new InMemDelayedEchoClient(3 second)
    system.actorOf(FooBarActor.props(id.toString, client.subscriptionManager, client), "foobar-" + id)
  }

  def createFooBarManager(): ActorRef = {
    val client = new InMemDelayedEchoClient(3 second)
    val ref = system.actorOf(RecoverableActorsManager.props(
      FooBarActor.persistenceCategory,
      id => FooBarActor.props(id, client.subscriptionManager, client)
    ), "foobar")
    ref ! RecoverAllActors
    expectMsg(ActorsRecovered)
    ref
  }

  override protected def beforeAll(): Unit = {
    Directory("snapshots").deleteRecursively()
    Directory("journal").deleteRecursively()
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}