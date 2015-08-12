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
import scala.concurrent.duration._

import scala.concurrent.{ExecutionContext, Promise}
import scala.language.postfixOps

object ServerApp extends App {
  import Json4sSupport._
  import rhttpc.api.json4s.Json4sSerializer._

  implicit val actorSystem = ActorSystem("rhttpc-server")
  implicit val materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  val rabbitMq = actorSystem.actorOf(Props[RabbitControl])

  val graph = FlowGraph.closed() { implicit builder =>
    import FlowGraph.Implicits._

    val source = RabbitSource(
      "rhttpc-request-source",
      rabbitMq,
      channel(qos = 3),
      consume(queue("rhttpc-request")),
      body(as[Correlated[HttpRequest]])
    ).akkaGraph.map {
      case t@(promise, correlated) =>
        actorSystem.log.info(s"Got $correlated")
        t
    }

    val httpClient = Http().outgoingConnection("sampleecho", 8082).mapAsync(3)(_.toStrict(1 minute))

    val sink = ConfirmedPublisherSink[Correlated[HttpResponse]](
      "rhttpc-response-sink",
      rabbitMq,
      ConfirmedMessage.factory[Correlated[HttpResponse]](QueuePublisher("rhttpc-response"))
    ).akkaGraph

    val unzipAckAndCorrelatedRequest = builder.add(Unzip[Promise[Unit], Correlated[HttpRequest]]())
    val unzipRequestAndCorrelationId = builder.add(UnzipWith[Correlated[HttpRequest], HttpRequest, String] { correlated =>
      (correlated.msg, correlated.correlationId)
    })

    val zipResponseAndCorrelationId = builder.add(ZipWith[HttpResponse, String, Correlated[HttpResponse]] { (msg, correlationId) =>
      val correlated = Correlated(msg, correlationId)
      actorSystem.log.info(s"Reply with $correlated")
      correlated
    })
    val zipAckAndCorrelatedResponse = builder.add(Zip[Promise[Unit], Correlated[HttpResponse]]())

    source ~> unzipAckAndCorrelatedRequest.in
              unzipAckAndCorrelatedRequest.out0                                                                                       ~> zipAckAndCorrelatedResponse.in0
              unzipAckAndCorrelatedRequest.out1 ~> unzipRequestAndCorrelationId.in
                                                   unzipRequestAndCorrelationId.out0 ~> httpClient ~> zipResponseAndCorrelationId.in0
                                                   unzipRequestAndCorrelationId.out1               ~> zipResponseAndCorrelationId.in1
                                                                                                      zipResponseAndCorrelationId.out ~> zipAckAndCorrelatedResponse.in1
                                                                                                                                         zipAckAndCorrelatedResponse.out ~> sink
  }

  graph.run()
}