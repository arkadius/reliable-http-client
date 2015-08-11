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
package reliablehttpc.sample

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import dispatch.{Http => DispatchHttp, as => dispatchAs, _}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object SampleApp extends App with Directives {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val client = new DelayedEchoClient {
    override def requestResponse(msg: String): Future[String] = {
      DispatchHttp(url("http://sampleecho:8082") << msg > dispatchAs.String)
    }
  }

  val manager = system.actorOf(FooBarsManger.props(client), "foobar")

  val route = path(Segment) { id =>
    (post & entity(as[String])) { msg =>
      complete {
        implicit val sendMsgTimeout = Timeout(5 seconds)
        (manager ? SendMsgToFooBar(id, SendMsg(msg))).map(_ => "OK")
      }
    } ~
    get {
      complete {
        implicit val currentStateTimeout = Timeout(5 seconds)
        (manager ? SendMsgToFooBar(id, CurrentState)).mapTo[FooBarState].map(_.toString)
      }
    }
  }

  Http().bindAndHandle(route, interface = "0.0.0.0", port = 8081)

}