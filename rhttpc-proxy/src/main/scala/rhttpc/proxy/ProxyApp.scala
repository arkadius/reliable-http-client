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
package rhttpc.proxy

import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import rhttpc.api.Correlated
import rhttpc.api.json4s.Json4sSerializer
import rhttpc.api.transport.amqp.{AmqpTransportCreateData, AmqpTransportFactory}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object ProxyApp extends App {

  implicit val actorSystem = ActorSystem("rhttpc-proxy")
  implicit val materializer = ActorMaterializer()
  import Json4sSerializer.formats
  import actorSystem.dispatcher

  val batchSize = 10
  val transport = AmqpTransportFactory.create(
    AmqpTransportCreateData[Correlated[Try[HttpResponse]], Correlated[HttpRequest]](actorSystem, qos = batchSize)
  )

  val publisher = transport.publisher("rhttpc-response")

  val httpClient = Http().superPool[String]().mapAsync(batchSize) {
    case (tryResponse, id) =>
      tryResponse match {
        case Success(response) => response.toStrict(1 minute).map(strict => (Success(strict), id))
        case failure => Future.successful((failure, id))
      }
  }

  // TODO: backpresure, move to flows
  val subscriber = transport.subscriber("rhttpc-request", actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = {
      case correlated@Correlated(req: HttpRequest, correlationId) =>
        actorSystem.log.debug(s"Got $correlated")
        val originalSender = sender()
        Source.single((req, correlationId)).via(httpClient).runForeach {
          case (tryResponse, id) =>
            val correlated = Correlated(tryResponse, id)
            val ackFuture = publisher.publish(correlated)
            ackFuture.onComplete {
              case Success(_) => actorSystem.log.debug(s"Publishing of $correlated successfully acknowledged")
              case Failure(ex) => actorSystem.log.error(s"Publishing of $correlated acknowledgement failed", ex)
            }
            ackFuture pipeTo originalSender
        }
    }
  })))

  subscriber.run()

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit = {
      transport.close()
    }
  })
}