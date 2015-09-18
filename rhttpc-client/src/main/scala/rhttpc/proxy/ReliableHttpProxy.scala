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
import akka.http.scaladsl.model.HttpResponse
import akka.stream.Materializer
import com.rabbitmq.client.Connection
import rhttpc.transport.PubSubTransport
import rhttpc.transport.amqp.{AmqpConnectionFactory, AmqpHttpTransportFactory}
import rhttpc.transport.api.Correlated

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ReliableHttpProxy {
  def apply()(implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    val connection = AmqpConnectionFactory.create(actorSystem)
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    new ReliableHttpProxy(batchSize = 10) {
      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
        recovered(super.close(), "closing ReliableHttpProxy").map { _ =>
          connection.close()
        }
      }
    }
  }

  def apply(connection: Connection, batchSize: Int)
           (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableHttpProxy = {
    implicit val transport = AmqpHttpTransportFactory.createResponseRequestTransport(connection)
    new ReliableHttpProxy(batchSize)
  }

  private def recovered[T](future: Future[T], action: String)
                          (implicit actorSystem: ActorSystem) = {
    import actorSystem.dispatcher
    future.recover {
      case ex =>
        actorSystem.log.error(ex, s"Exception while $action")
    }
  }
}

class ReliableHttpProxy(protected val batchSize: Int)
                       (implicit actorSystem: ActorSystem,
                        materialize: Materializer,
                        transport: PubSubTransport[Correlated[Try[HttpResponse]]]) extends ReliableHttpSender {

  private val publisher = transport.publisher("rhttpc-response")

  override protected def handleResponse(correlatedResponse: Correlated[Try[HttpResponse]])
                                       (implicit ec: ExecutionContext, log: LoggingAdapter): Future[Unit] = {
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