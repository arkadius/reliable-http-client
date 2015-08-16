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
package rhttpc.server

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import com.spingo.op_rabbit._
import com.spingo.op_rabbit.consumer.Directives._
import com.spingo.op_rabbit.stream._
import rhttpc.api.Correlated
import rhttpc.api.transport.amqp.QueuePublisherDeclaringQueueIfNotExist

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object ServerApp extends App {
  import Json4sSupport._
  import rhttpc.api.json4s.Json4sSerializer._

  implicit val actorSystem = ActorSystem("rhttpc-server")
  implicit val materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  val rabbitMq = actorSystem.actorOf(Props[RabbitControl])

  val graph = FlowGraph.closed() { implicit builder =>
    import FlowGraph.Implicits._

    val batchSize = 10

    val source = RabbitSource(
      "rhttpc-request-source",
      rabbitMq,
      channel(qos = batchSize),
      consume(queue("rhttpc-request")),
      body(as[Correlated[HttpRequest]])
    ).akkaGraph.map {
      case t@(promise, correlated) =>
        actorSystem.log.debug(s"Got $correlated")
        t
    }

    val httpClient = Http().superPool[String]().mapAsync(batchSize) {
      case (tryResponse, id) =>
        tryResponse match {
          case Success(response) => response.toStrict(1 minute).map(strict => (Success(strict), id))
          case failure => Future.successful((failure, id))
        }
    }

    val sink = ConfirmedPublisherSink[Correlated[Try[HttpResponse]]](
      "rhttpc-response-sink",
      rabbitMq,
      ConfirmedMessage.factory[Correlated[Try[HttpResponse]]](QueuePublisherDeclaringQueueIfNotExist("rhttpc-response"))
    ).akkaGraph

    val unzipAckAndCorrelatedRequest = builder.add(UnzipWith[(Promise[Unit], Correlated[HttpRequest]), Promise[Unit], (HttpRequest, String)] {
      case (ackPromise, correlated@Correlated(request, id)) =>
        ackPromise.future.onComplete {
          case Success(_) => actorSystem.log.debug(s"Publishing of $correlated successfully acknowledged")
          case Failure(ex) => actorSystem.log.error(s"Publishing of $correlated acknowledgement failed", ex)
        }
        (ackPromise, (request, id))
    })

    val zipAckAndCorrelatedResponse = builder.add(ZipWith[Promise[Unit], (Try[HttpResponse], String), (Promise[Unit], Correlated[Try[HttpResponse]])] {
      case (ackPromise, (tryResponse, id)) =>
        (ackPromise, Correlated(tryResponse, id))
    })

    source ~> unzipAckAndCorrelatedRequest.in
              unzipAckAndCorrelatedRequest.out0                 ~> zipAckAndCorrelatedResponse.in0
              unzipAckAndCorrelatedRequest.out1 ~> httpClient   ~> zipAckAndCorrelatedResponse.in1
                                                                   zipAckAndCorrelatedResponse.out ~> sink
  }

  graph.run()
}