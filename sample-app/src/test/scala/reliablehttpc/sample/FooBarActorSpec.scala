package reliablehttpc.sample

import akka.actor._
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration._

class FooBarActorSpec extends TestKit(ActorSystem()) with ImplicitSender with fixture.FlatSpecLike with BeforeAndAfterAll {
  import system.dispatcher

  it should "be in init state initially" in { fooBarActor =>
    fooBarActor ! CurrentState
    expectMsg(InitState)
  }

  it should "go into waiting state after send msg command" in { fooBarActor =>
    fooBarActor ! SendMsg("foo")
    fooBarActor ! CurrentState
    expectMsg(WaitingForResponseState)
  }

  it should "go into foo state after echo response" in { fooBarActor =>
    fooBarActor ! SendMsg("foo")
    val scheduled = system.scheduler.schedule(0.millis, 100.millis, fooBarActor, CurrentState)
    fishForMessage(3 seconds) {
      case WaitingForResponseState => false
      case FooState => true
    }
    scheduled.cancel()
  }

  override type FixtureParam = ActorRef

  var id = 0

  override protected def withFixture(test: OneArgTest): Outcome = {
    id += 1
    val client = new InMemDelayedEchoClient(1 second)
    test(system.actorOf(Props(new FooBarActor(id.toString, client)), "foobar-" + id))
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
