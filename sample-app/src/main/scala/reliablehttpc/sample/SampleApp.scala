package reliablehttpc.sample

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import dispatch.{Http => DispatchHttp, as => dispatchAs, _}

import scala.concurrent.Future

object SampleApp extends App with Directives {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val client = new DelayedEchoClient {
    override def requestResponse(msg: String): Future[String] = {
      DispatchHttp(url("http://localhost:8082") << "foo" > dispatchAs.String)
    }
  }

  val route = path(Segment) { id =>
    (post & entity(as[String])) { msg =>
      val fooBar = system.actorOf(Props(new FooBarActor(id, client)), s"foobar-$id")
      fooBar ! SendMsg(msg)
      complete("OK")
    }
  }

  Http().bindAndHandle(route, interface = "0.0.0.0", port = 8081)
}