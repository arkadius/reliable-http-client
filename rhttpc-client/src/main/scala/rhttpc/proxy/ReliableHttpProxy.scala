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
import rhttpc.proxy.processor.{AckAction, HttpResponseProcessor, PublishingEveryResponseProcessor}
import rhttpc.transport.amqp.{AmqpConnectionFactory, AmqpHttpTransportFactory}
import rhttpc.transport.api.Correlated
import rhttpc.transport.{PubSubTransport, Publisher}
import rhttpc.client._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object ReliableHttpProxy {
  def apply()(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    new ReliableHttpProxy(PublishingEveryResponseProcessor, batchSize = 10) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        recovered(super.close(), "closing ReliableHttpProxy").map { _ =>
          connection.close()
        }
      }
    }
  }

  def apply(connection: Connection, responseProcessor: HttpResponseProcessor, batchSize: Int)
           (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    new ReliableHttpProxy(responseProcessor, batchSize)
  }
}

class ReliableHttpProxy(responseProcessor: HttpResponseProcessor, protected val batchSize: Int)
                       (implicit actorSystem: ActorSystem,
                        materialize: Materializer,
                        transport: PubSubTransport[Correlated[Try[HttpResponse]]]) extends ReliableHttpSender {

  private val publisher = transport.publisher("rhttpc-response")

  override protected def handleResponse(tryResponse: Try[HttpResponse])
                                       (forRequest: HttpRequest, correlationId: String)
                                       (implicit ec: ExecutionContext, log: LoggingAdapter): Future[Unit] = {
    val context = HttpProxyContext(forRequest, correlationId, publisher, log, ec)
    responseProcessor.handleResponse(context).applyOrElse(tryResponse, (_: Try[HttpResponse]) => AckAction())
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