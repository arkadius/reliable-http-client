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
package rhttpc.transport.amqp

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import com.rabbitmq.client.{Connection, ConnectionFactory}
import org.json4s.Formats
import rhttpc.transport.api.Correlated
import rhttpc.transport.json4s.Json4sSerializer
import rhttpc.transport.{PubSubTransport, PubSubTransportFactory, TransportCreateData}

import scala.util.{Failure, Success, Try}

object AmqpTransportFactory extends PubSubTransportFactory {
  override type DataT[P, S] = AmqpTransportCreateData[P, S]

  override def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): PubSubTransport[PubMsg] = {
    new AmqpTransport[PubMsg, SubMsg](data)
  }
}

object AmqpHttpTransportFactory {
  def createRequestResponseTransport(connection: Connection)
                                    (implicit actorSystem: ActorSystem): PubSubTransport[Correlated[HttpRequest]] = {
    import Json4sSerializer.formats
    AmqpTransportFactory.create(
      AmqpTransportCreateData(actorSystem, connection)
    )
  }

  def createResponseRequestTransport(connection: Connection)
                                    (implicit actorSystem: ActorSystem): PubSubTransport[Correlated[Try[HttpResponse]]] = {
    import Json4sSerializer.formats
    AmqpTransportFactory.create(
      AmqpTransportCreateData(actorSystem, connection)
    )
  }
}

case class AmqpTransportCreateData[PubMsg, SubMsg](actorSystem: ActorSystem, connection: Connection, qos: Int = 10)
                                                  (implicit val subMsgManifest: Manifest[SubMsg],
                                                   val formats: Formats) extends TransportCreateData[PubMsg, SubMsg]

object AmqpConnectionFactory {
  def create(actorSystem: ActorSystem): Connection = {
    import collection.convert.wrapAsScala._
    val factory = new ConnectionFactory()
    factory.setAutomaticRecoveryEnabled(true)

    retry(n = 10, delay = 5000) {
      Try { // Could By IOException or TimeoutException
        val hosts = actorSystem.settings.config.getStringList("rabbitmq.hosts")
        val addresses = hosts.map(com.rabbitmq.client.Address.parseAddress).toArray
        factory.newConnection(addresses)
      }
    }
  }

  private def retry[T](n: Int, delay: Long)(fn: => Try[T]): T = {
    fn match {
      case Success(x) => x
      case _ if n > 1 =>
        Thread.sleep(delay)
        retry(n - 1, delay)(fn)
      case Failure(e) => throw e
    }
  }
}