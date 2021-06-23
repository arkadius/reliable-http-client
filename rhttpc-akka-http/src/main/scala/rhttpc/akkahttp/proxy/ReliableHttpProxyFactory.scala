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
package rhttpc.akkahttp.proxy

import akka.NotUsed
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import org.slf4j.LoggerFactory
import rhttpc.client.protocol.{Correlated, Request}
import rhttpc.client.proxy._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

object ReliableHttpProxyFactory {

  private lazy val logger = LoggerFactory.getLogger(getClass)

  def send(successRecognizer: SuccessHttpResponseRecognizer, batchSize: Int, parallelConsumers: Int)
          (request: Request[HttpRequest])
          (implicit actorSystem: ActorSystem, materialize: Materializer): Future[HttpResponse] = {
    import actorSystem.dispatcher
    send(prepareHttpFlow(batchSize * parallelConsumers), successRecognizer)(request.correlated)
  }

  private def prepareHttpFlow(parallelism: Int)
                             (implicit actorSystem: ActorSystem, materialize: Materializer):
    Flow[(HttpRequest, String), HttpResponse, NotUsed] = {

    import actorSystem.dispatcher
    Http().superPool[String]().mapAsync(parallelism) {
      case (tryResponse, id) =>
        tryResponse match {
          case Success(response) =>
            response.toStrict(1 minute)
          case Failure(ex) =>
            Future.failed(ex)
        }
    }
  }

  private def send(httpFlow: Flow[(HttpRequest, String), HttpResponse, Any], successRecognizer: SuccessHttpResponseRecognizer)
                  (corr: Correlated[HttpRequest])
                  (implicit ec: ExecutionContext, materialize: Materializer): Future[HttpResponse] = {
    import scala.collection.compat._
    import scala.jdk.CollectionConverters._
    logger.debug(
      s"""Sending request for ${corr.correlationId} to ${corr.msg.getUri()}. Headers:
         |${corr.msg.getHeaders().asScala.toSeq.map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
         |Body:
         |${corr.msg.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
    val logResp = logResponse(corr) _
    val responseFuture = Source.single((corr.msg, corr.correlationId)).via(httpFlow).runWith(Sink.head)
    responseFuture.onComplete {
      case Failure(ex) =>
        logger.error(s"Got failure for ${corr.correlationId} to ${corr.msg.getUri()}", ex)
      case Success(_) =>
    }
    for {
      response <- responseFuture
      transformedToFailureIfNeed <- {
        if (successRecognizer.isSuccess(response)) {
          logResp(response, "success response")
          Future.successful(response)
        } else {
          logResp(response, "response recognized as non-success")
          Future.failed(NonSuccessResponse)
        }
      }
    } yield transformedToFailureIfNeed
  }

  private def logResponse(corr: Correlated[HttpRequest])
                         (response: HttpResponse, additionalInfo: String): Unit = {
    import scala.collection.compat._
import scala.jdk.CollectionConverters._
    logger.debug(
      s"""Got $additionalInfo for ${corr.correlationId} to ${corr.msg.getUri()}. Status: ${response.status.value}. Headers:
         |${response.getHeaders().asScala.toSeq.map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
         |Body:
         |${response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
  }

}