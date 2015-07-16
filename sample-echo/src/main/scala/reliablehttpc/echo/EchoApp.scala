package reliablehttpc.echo

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.pattern._
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.concurrent.duration._

object EchoApp extends App with Directives {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val route = (post & entity(as[String])) { msg =>
    complete {
      after(10 seconds, system.scheduler)(Future.successful(msg))
    }
  }

  Http().bindAndHandle(route, interface = "0.0.0.0", port = 8082)
}