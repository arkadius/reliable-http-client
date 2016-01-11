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
import rhttpc.akkahttp.amqp.AmqpJson4sHttpTransportFactory
import rhttpc.client._
import rhttpc.client.protocol.{Correlated, WithRetryingHistory}
import rhttpc.client.proxy._
import rhttpc.transport._
import rhttpc.transport.amqp.AmqpConnectionFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object ReliableHttpProxy {
  def apply(successRecognizer: SuccessRecognizer[HttpResponse] = AcceptSuccessHttpStatus,
            failureHandleStrategyChooser: FailureResponseHandleStrategyChooser = SkipAll)
           (implicit actorSystem: ActorSystem,
            materialize: Materializer): Future[ReliableHttpProxy] = {
    import actorSystem.dispatcher
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { case connection =>
      val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
      val requestQueueName = actorSystem.settings.config.getString("rhttpc.request-queue.name")
      val responseQueueName = actorSystem.settings.config.getString("rhttpc.response-queue.name")
      val responsePublisher = responseRequestTransport.publisher(OutboundQueueData(responseQueueName))
      val batchSize = actorSystem.settings.config.getInt("rhttpc.batchSize")
      new ReliableHttpProxy(
        prepareSubscriber(responseRequestTransport, batchSize, requestQueueName),
        requestResponseTransport.publisher(OutboundQueueData(requestQueueName, delayed = true)),
        prepareHttpFlow(batchSize),
        successRecognizer,
        failureHandleStrategyChooser,
        PublishMsg(responsePublisher)) {

        override def close()(implicit ec: ExecutionContext): Future[Unit] = {
          recovered(super.close(), "closing ReliableHttpProxy").map { _ =>
            Try(responsePublisher.close())
            connection.close()
          }
        }
      }
    }
  }

  def apply(connection: Connection,
            successRecognizer: SuccessRecognizer[HttpResponse],
            failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
            handleResponse: Correlated[Try[HttpResponse]] => Future[Unit])
           (implicit actorSystem: ActorSystem,
            materialize: Materializer): ReliableHttpProxy = {
    val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
    val requestQueueName = actorSystem.settings.config.getString("rhttpc.request-queue.name")
    val batchSize = actorSystem.settings.config.getInt("rhttpc.batchSize")
    new ReliableHttpProxy(
      prepareSubscriber(responseRequestTransport, batchSize, requestQueueName),
      requestResponseTransport.publisher(OutboundQueueData(requestQueueName, delayed = true)),
      prepareHttpFlow(batchSize),
      successRecognizer,
      failureHandleStrategyChooser,
      handleResponse
    )
  }

  private def prepareSubscriber(transport: PubSubTransport[_, WithRetryingHistory[Correlated[HttpRequest]]], batchSize: Int, requestQueueName: String)
                               (implicit actorSystem: ActorSystem):
  (ActorRef) => Subscriber[WithRetryingHistory[Correlated[HttpRequest]]] =
    transport.subscriber(InboundQueueData(requestQueueName, batchSize), _)

  private def prepareHttpFlow(batchSize: Int)
                             (implicit actorSystem: ActorSystem,
                              materialize: Materializer) = {
    import actorSystem.dispatcher
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

}

class ReliableHttpProxy(subscriberForConsumer: ActorRef => Subscriber[WithRetryingHistory[Correlated[HttpRequest]]],
                        requestPublisher: Publisher[WithRetryingHistory[Correlated[HttpRequest]]],
                        httpFlow: Flow[(HttpRequest, String), (Try[HttpResponse], String), Any],
                        successRecognizer: SuccessRecognizer[HttpResponse],
                        failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                        handleResponse: Correlated[Try[HttpResponse]] => Future[Unit])
                       (implicit actorSystem: ActorSystem,
                        materialize: Materializer)
  extends ReliableProxy[HttpRequest, HttpResponse](
    subscriberForConsumer,
    requestPublisher,
    successRecognizer,
    failureHandleStrategyChooser,
    handleResponse) {

  import actorSystem.dispatcher

  protected def send(corr: Correlated[HttpRequest]): Future[Try[HttpResponse]] = {
    import collection.convert.wrapAsScala._
    log.debug(
      s"""Sending request for ${corr.correlationId} to ${corr.msg.getUri()}. Headers:
         |${corr.msg.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
         |Body:
         |${corr.msg.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
    Source.single((corr.msg, corr.correlationId)).via(httpFlow).runWith(Sink.head).map {
      case (tryResponse, correlationId) =>
        tryResponse match {
          case Success(response) =>
            log.debug(
              s"""Got response for ${corr.correlationId} to ${corr.msg.getUri()}. Status: ${response.status.value}. Headers:
                 |${response.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
                 |Body:
                 |${response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
            )
          case Failure(ex) =>
            log.error(s"Got failure for ${corr.correlationId} to ${corr.msg.getUri()}", ex)
        }
        tryResponse
    }
  }

}