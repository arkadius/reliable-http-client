package rhttpc.client

import akka.actor.{ActorRef, ActorSystem, Status}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import org.scalatest._
import rhttpc.api.Correlated
import rhttpc.api.transport.{PubSubTransport, Publisher, Subscriber}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

class ReliableClientSpec extends TestKit(ActorSystem("ReliableClientSpec")) with fixture.FlatSpecLike with ImplicitSender with Matchers {

  import system.dispatcher
  implicit val timeout = Timeout(3 seconds)

  it should "send request and reply after subscription registration" in { fixture =>
    val sendFuture = fixture.client.send("foo").toFuture
    fixture.transport.publicationPromise.success(Unit)
    fixture.transport.replySubscriptionPromise.completeWith(Future {
      Thread.sleep(1000) // wait for registration of promise of subscription
      "bar"
    })

    Await.result(sendFuture, 5 seconds) shouldEqual "bar"
  }

  it should "send request and reply before subscription registration" in { fixture =>
    val sendFuture = fixture.client.send("foo").toFuture
    fixture.transport.replySubscriptionPromise.success("bar")
    fixture.transport.publicationPromise.success(Unit)

    Await.result(sendFuture, 5 seconds) shouldEqual "bar"
  }

  case class FixtureParam(client: ReliableClient[String], transport: MockTransport)

  override protected def withFixture(test: OneArgTest): Outcome = {
    implicit val transport = new MockTransport
    val subMgr = SubscriptionManager()
    val client = new ReliableClient[String](subMgr)
    try {
      test(FixtureParam(client, transport))
    } finally {
      Await.result(client.close(), 10 seconds)
    }
  }
}

class MockTransport(implicit ec: ExecutionContext) extends PubSubTransport[Correlated[String], Correlated[String]] {
  var publicationPromise: Promise[Unit] = _
  var replySubscriptionPromise: Promise[String] = _
  private var consumer: ActorRef = _

  override def publisher(queueName: String): Publisher[Correlated[String]] = new Publisher[Correlated[String]] {
    override def publish(request: Correlated[String]): Future[Unit] = {
      publicationPromise = Promise[Unit]()
      replySubscriptionPromise = Promise[String]()
      replySubscriptionPromise.future.onComplete {
        case Success(msg) =>
          consumer ! Correlated(msg, request.correlationId)
        case Failure(ex) =>
          consumer ! Correlated(Status.Failure(ex), request.correlationId)
      }
      publicationPromise.future
    }

    override def close(): Future[Unit] = Future.successful(Unit)
  }

  override def subscriber(queueName: String, consumer: ActorRef): Subscriber = new Subscriber {
    MockTransport.this.consumer = consumer

    override def run(): Unit = {}

    override def stop(): Future[Unit] = Future.successful(Unit)
  }
}