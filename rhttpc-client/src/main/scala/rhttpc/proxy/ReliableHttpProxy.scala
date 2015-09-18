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
import rhttpc.transport.PubSubTransport
import rhttpc.transport.amqp.{AmqpConnectionFactory, AmqpTransportCreateData, AmqpTransportFactory}
import rhttpc.transport.api.Correlated
import rhttpc.transport.json4s.Json4sSerializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ReliableHttpProxy {
  def apply()(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    import Json4sSerializer.formats
    val batchSize = 10
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[Try[HttpResponse]], Correlated[HttpRequest]](actorSystem, connection, qos = batchSize)
    )
    new ReliableHttpProxy(batchSize) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        super.close().map { _ =>
          connection.close()
        }
      }
    }
  }

  def apply(connection: Connection, batchSize: Int)
           (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    import Json4sSerializer.formats
    implicit val transport = AmqpTransportFactory.create(
      AmqpTransportCreateData[Correlated[Try[HttpResponse]], Correlated[HttpRequest]](actorSystem, connection, qos = batchSize)
    )
    new ReliableHttpProxy(batchSize)
  }
}

class ReliableHttpProxy(protected val batchSize: Int)
                       (implicit actorSystem: ActorSystem,
                        materialize: Materializer,
                        transport: PubSubTransport[Correlated[Try[HttpResponse]]]) extends ReliableHttpSender {

  private val publisher = transport.publisher("rhttpc-response")

  override protected def handleResponse(originalSender: ActorRef, correlatedResponse: Correlated[Try[HttpResponse]])
                                       (log: LoggingAdapter)
                                       (implicit ec: ExecutionContext): Future[Unit] = {
    val ackFuture = publisher.publish(correlatedResponse)
    ackFuture.onComplete {
      case Success(_) => log.debug(s"Publishing of $correlatedResponse successfully acknowledged")
      case Failure(ex) => log.error(s"Publishing of $correlatedResponse acknowledgement failed", ex)
    }
    ackFuture
  }

  override def close()(implicit ec: ExecutionContext): Future[Unit] = {
    super.close().map { _ =>
      publisher.close()
    }
  }
}