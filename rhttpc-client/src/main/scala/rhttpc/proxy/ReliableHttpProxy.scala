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

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.rabbitmq.client.Connection
import rhttpc.client._
import rhttpc.proxy.handler._
import rhttpc.transport.amqp.{AmqpConnectionFactory, AmqpHttpTransportFactory}
import rhttpc.transport.protocol.Correlated
import rhttpc.transport.{PubSubTransport, Publisher}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ReliableHttpProxy {
  def apply()(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    import actorSystem.dispatcher
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    // TODO: from configuration
    val handler = new EveryResponseHandler(PublishingSuccessStatusInResponseProcessor)
    new ReliableHttpProxy(handler, batchSize = 10) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        recovered(super.close(), "closing ReliableHttpProxy").map { _ =>
          connection.close()
        }
      }
    }
  }

  def apply(connection: Connection, responseHandler: HttpResponseHandler, batchSize: Int)
           (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    import actorSystem.dispatcher
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    new ReliableHttpProxy(responseHandler, batchSize)
  }
}

class ReliableHttpProxy(responseHandler: HttpResponseHandler, protected val batchSize: Int)
                       (implicit actorSystem: ActorSystem,
                        materialize: Materializer,
                        transport: PubSubTransport[Correlated[Try[HttpResponse]]]) extends ReliableHttpSender {


  private val responseQueueName = actorSystem.settings.config.getString("rhttpc.response-queue.name")

  private val publisher = transport.publisher(responseQueueName)

  override protected def handleResponse(tryResponse: Try[HttpResponse])
                                       (forRequest: HttpRequest, correlationId: String)
                                       (implicit ec: ExecutionContext, log: LoggingAdapter): Future[Unit] = {
    val responseProcessor = responseHandler.handleForRequest.applyOrElse(forRequest, (_: HttpRequest) => AckingProcessor)
    val context = HttpProxyContext(forRequest, correlationId, publisher, log, ec)
    responseProcessor.processResponse(tryResponse, context)
  }

  override def close()(implicit ec: ExecutionContext): Future[Unit] = {
    recovered(super.close(), "closing ReliableHttpSender").map { _ =>
      publisher.close()
    }
  }
}

case class HttpProxyContext(request: HttpRequest,
                            correlationId: String,
                            publisher: Publisher[Correlated[Try[HttpResponse]]],
                            log: LoggingAdapter,
                            ec: ExecutionContext) {
  implicit val executionContext = ec
}