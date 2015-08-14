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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.pattern._
import akka.persistence.{RecoverAllActors, RecoverableActorsManger, SendMsgToChild}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import rhttpc.api.Correlated
import rhttpc.api.json4s.Json4sSerializer
import rhttpc.api.transport.amqp.{AmqpTransportCreateData, AmqpTransportFactory}
import rhttpc.client._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

object SampleApp extends App with Directives {
  import Json4sSerializer.formats
  implicit val system = ActorSystem("rhttpc-sample")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  implicit val transport = AmqpTransportFactory.create(
    AmqpTransportCreateData[Correlated[HttpRequest], Correlated[HttpResponse]](system)
  )

  private val rhttpc = ReliableHttp()
  val client = new DelayedEchoClient {
    override def requestResponse(msg: String)(implicit ec: ExecutionContext): PublicationPromise = {
      rhttpc.send(HttpRequest().withMethod(HttpMethods.POST).withEntity(msg))
    }
  }

  val subscriptionManager = rhttpc.subscriptionManager

  val manager = system.actorOf(RecoverableActorsManger.props(
    FooBarActor.persistenceCategory,
    id => FooBarActor.props(id, subscriptionManager, client)
  ), "foobar")

  Await.result((manager ? RecoverAllActors)(Timeout(5 seconds)), 10 seconds)

  subscriptionManager.run()

  val route = path(Segment) { id =>
    (post & entity(as[String])) { msg =>
      complete {
        implicit val sendMsgTimeout = Timeout(5 seconds)
        (manager ? SendMsgToChild(id, SendMsg(msg))).map(_ => "OK")
      }
    } ~
    get {
      complete {
        implicit val currentStateTimeout = Timeout(5 seconds)
        (manager ? SendMsgToChild(id, CurrentState)).mapTo[FooBarState].map(_.toString)
      }
    }
  }

  Http().bindAndHandle(route, interface = "0.0.0.0", port = 8081)
}