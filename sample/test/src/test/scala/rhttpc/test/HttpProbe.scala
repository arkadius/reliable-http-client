package rhttpc.test

import dispatch._
import dispatch.Defaults.timer

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

case class HttpProbe(port: Int) {
  def await(atMostSeconds: Int = 15)(implicit ec: ExecutionContext) = {
    val http = Http().configure(_.setConnectTimeout(500).setRequestTimeout(500))
    val future = retry.Pause(max = atMostSeconds * 2) { () => // default delay is 0,5 sec
      http(url(s"http://localhost:$port") OK as.String).map(Some(_)).recover {
        case NonFatal(ex) => None
      }
    }
    Await.result(future, atMostSeconds seconds)
  }
}
