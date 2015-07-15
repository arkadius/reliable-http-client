package reliablehttpc.sample

import akka.actor.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.pattern._

trait DelayedEchoClient {
  def requestResponse(msg: String): Future[String]
}

