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
import org.scalatest.flatspec.AnyFlatSpecLike
import rhttpc.akkapersistence.{ActorsRecovered, RecoverAllActors, RecoverableActorsManager, SendMsgToChild, StateSaved}

import scala.concurrent.duration._
import scala.reflect.io.Directory

class FooBarActorSpec extends TestKit(ActorSystem()) with ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll {
  import system.dispatcher

  // FIXME: tests doesn't pass on circle-ci
  ignore should "be in init state initially" in withNextFooBar { fooBarActor =>
    fooBarActor ! CurrentState
    expectMsg(InitState)
  }

  ignore should "go into waiting state after send msg command" in withNextFooBar { fooBarActor =>
    fooBarActor ! SendMsg("foo")
    fooBarActor ! CurrentState
    expectMsg(WaitingForResponseState)
  }

  ignore should "go into foo state after echo response" in withNextFooBar { fooBarActor =>
    TestProbe().send(fooBarActor, SendMsg("foo"))
    val scheduled = system.scheduler.scheduleAtFixedRate(0.millis, 100.millis, fooBarActor, CurrentState)
    fishForMessage(10 seconds) {
      case WaitingForResponseState => false
      case FooState => true
    }
    scheduled.cancel()
  }

  ignore should "restore persited waiting state and continue with response" in {
    val id = "persisted"
    val fooBarActor = createFooBar(id)
    fooBarActor ! SendMsg("foo")
    expectMsg(10 seconds, StateSaved)

    watch(fooBarActor)
    fooBarActor ! PoisonPill
    expectTerminated(fooBarActor)

    val restoredActor = createFooBar(id)
    restoredActor ! CurrentState
    expectMsg(WaitingForResponseState)
    restoredActor ! "foo"
    restoredActor ! CurrentState
    expectMsg(FooState)
    gracefulStop(restoredActor)
  }

  // FIXME: manager is not killed, from time to time there is bad state of foobar
  ignore should "restore persisted actors just after manager init" in {
    (0 to 9).foreach { i =>
      val mgr = createFooBarManager()

      val id = s"saved_$i"
      mgr ! SendMsgToChild(id, SendMsg("foo"))
      expectMsg(10 seconds, StateSaved)

      watch(mgr)
      mgr ! PoisonPill
      expectTerminated(mgr)

      val restoredMgr = createFooBarManager()
      restoredMgr ! SendMsgToChild(id, CurrentState)
      expectMsg(WaitingForResponseState)
      restoredMgr ! SendMsgToChild(id, "foo")
      expectMsg(StateSaved)
      restoredMgr ! SendMsgToChild(id, CurrentState)
      expectMsg(FooState)

      watch(restoredMgr)
      mgr ! PoisonPill
      expectTerminated(restoredMgr, 10 seconds)
    }
    ()
  }

  val id = new AtomicInteger(0)

  def withNextFooBar(test: ActorRef => Unit) = {
    val fooBarACtor = createFooBar(id.getAndIncrement().toString)
    test(fooBarACtor)
    gracefulStop(fooBarACtor)
  }

  def gracefulStop(fooBarActor: ActorRef): Unit = {
    watch(fooBarActor)
    fooBarActor ! StopYourself
    expectTerminated(fooBarActor)
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
    ), FooBarActor.persistenceCategory)
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
