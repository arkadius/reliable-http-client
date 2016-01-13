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

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.rabbitmq.client.Connection
import org.slf4j.LoggerFactory
import rhttpc.akkahttp.ReliableHttpProxy
import rhttpc.akkahttp.amqp.AmqpJson4sHttpTransportFactory
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.Correlated
import rhttpc.client.proxy._
import rhttpc.transport.amqp.AmqpConnectionFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class ReliableHttpProxyFactory(implicit actorSystem: ActorSystem, materialize: Materializer) {

  private lazy val log = LoggerFactory.getLogger(getClass)

  import actorSystem.dispatcher

  def publishingResponsesWithOwnConnection(successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                                           batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                           queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                           retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): Future[ReliableHttpProxy] = {
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { connection =>
      val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
      ReliableProxyFactory().publishingResponses(
        responseRequestTransport = responseRequestTransport,
        requestPublisherTransport = requestResponseTransport,
        send = send(prepareHttpFlow(batchSize), successRecognizer),
        batchSize = batchSize,
        queuesPrefix = queuesPrefix,
        retryStrategy = retryStrategy,
        additionalCloseAction = {
          recovered(connection.close(), "closing amqp connection")
          Future.successful(Unit)
        }
      )
    }
  }

  def publishingResponses(connection: Connection,
                          successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                          batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                          queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                          retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): ReliableHttpProxy = {
    val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
    ReliableProxyFactory().publishingResponses(
      responseRequestTransport = responseRequestTransport,
      requestPublisherTransport = requestResponseTransport,
      send = send(prepareHttpFlow(batchSize), successRecognizer),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }

  def skippingResponsesWithOwnConnection(successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                                         batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                         queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                         retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): Future[ReliableHttpProxy] = {
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { connection =>
      val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
      ReliableProxyFactory().skippingResponses(
        requestSubscriberTransport = responseRequestTransport,
        requestPublisherTransport = requestResponseTransport,
        send = send(prepareHttpFlow(batchSize), successRecognizer),
        batchSize = batchSize,
        queuesPrefix = queuesPrefix,
        retryStrategy = retryStrategy,
        additionalCloseAction = {
          recovered(connection.close(), "closing amqp connection")
          Future.successful(Unit)
        }
      )
    }
  }

  def skippingResponses(connection: Connection,
                        successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                        batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                        queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                        retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): ReliableHttpProxy = {
    val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
    ReliableProxyFactory().skippingResponses(
      requestSubscriberTransport = responseRequestTransport,
      requestPublisherTransport = requestResponseTransport,
      send = send(prepareHttpFlow(batchSize), successRecognizer),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }




  private def prepareHttpFlow(batchSize: Int) = {
    Http().superPool[String]().mapAsync(batchSize) {
      case (tryResponse, id) =>
        tryResponse match {
          case Success(response) =>
            response.toStrict(1 minute).map(strict => (Success(strict), id))
          case failure =>
            Future.successful((failure, id))
        }
    }
  }

  private def send(httpFlow: Flow[(HttpRequest, String), (Try[HttpResponse], String), Any], successRecognizer: SuccessHttpResponseRecognizer)
                  (corr: Correlated[HttpRequest]): Future[Try[HttpResponse]] = {
    import collection.convert.wrapAsScala._
    log.debug(
      s"""Sending request for ${corr.correlationId} to ${corr.msg.getUri()}. Headers:
          |${corr.msg.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
          |Body:
          |${corr.msg.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
    val logResp = logResponse(corr) _
    Source.single((corr.msg, corr.correlationId)).via(httpFlow).runWith(Sink.head).map {
      case (tryResponse, correlationId) =>
        tryResponse match {
          case success@Success(response) if successRecognizer.isSuccess(response) =>
            logResp(response, "success response")
            success
          case Success(response) =>
            logResp(response, "response recognized as non-success")
            Failure(NonSuccessResponse)
          case failure@Failure(ex) =>
            log.error(s"Got failure for ${corr.correlationId} to ${corr.msg.getUri()}", ex)
            failure
        }
    }
  }

  private def logResponse(corr: Correlated[HttpRequest])
                         (response: HttpResponse, additionalInfo: String) = {
    import collection.convert.wrapAsScala._
    log.debug(
      s"""Got $additionalInfo for ${corr.correlationId} to ${corr.msg.getUri()}. Status: ${response.status.value}. Headers:
          |${response.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
          |Body:
          |${response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
  }

}