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

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.spingo.op_rabbit.RabbitControl
import rhttpc.client._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.language.postfixOps

object SampleApp extends App with Directives {
  implicit val system = ActorSystem("rhttpc-sample")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  implicit val rabbitControl = RabbitControlActor(system.actorOf(Props[RabbitControl]))


  val client = new DelayedEchoClient {
//    override def requestResponse(msg: String): Future[String] = {
//      DispatchHttp(url("http://sampleecho:8082") << msg > dispatchAs.String)
//    }
    private val rhttpc = ReliableHttp()

    override def requestResponse(msg: String)(implicit ec: ExecutionContext): Future[DoRegisterSubscription] =
      rhttpc.send(HttpRequest(method = HttpMethods.POST).withEntity(msg))
  }

  val subscriptionManager = SubscriptionManager()
  Await.result(subscriptionManager.initialized, 5 seconds)

  val manager = system.actorOf(FooBarsManger.props(subscriptionManager, client), "foobar")
  Await.result((manager ? RecoverAllFooBars)(Timeout(5 seconds)), 10 seconds)

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