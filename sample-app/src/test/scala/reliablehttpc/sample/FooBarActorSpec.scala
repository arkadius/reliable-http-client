package reliablehttpc.sample

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration._
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
    fooBarActor ! SendMsg("foo")
    val scheduled = system.scheduler.schedule(0.millis, 100.millis, fooBarActor, CurrentState)
    fishForMessage(3 seconds) {
      case WaitingForResponseState => false
      case FooState => true
    }
    scheduled.cancel()
  }

  it should "restore persited waiting state and continue with response" in {
    val id = "persisted"
    val fooBarActor = createFooBar(id)
    fooBarActor ! SendMsg("foo")

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
  }

  val id = new AtomicInteger(0)

  def withNextFooBar(test: ActorRef => Unit) = {
    test(createFooBar(id.incrementAndGet().toString))
  }

  def createFooBar(id: String): ActorRef = {
    val client = new InMemDelayedEchoClient(1 second)
    system.actorOf(Props(new FooBarActor(id.toString, client)), "foobar-" + id)
  }

  override protected def beforeAll(): Unit = {
    Directory("snapshots").deleteRecursively()
    Directory("journal").deleteRecursively()
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
