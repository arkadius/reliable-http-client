package reliablehttpc.sample

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class InMemDelayedEchoClient(delay: FiniteDuration)(implicit system: ActorSystem) extends DelayedEchoClient {
  import system.dispatcher

  override def requestResponse(msg: String): Future[String] = after(delay, system.scheduler)(Future.successful(msg))
}
