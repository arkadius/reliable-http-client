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
package rhttpc.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import com.rabbitmq.client.Connection
import rhttpc.akkahttp.amqp.AmqpJson4sHttpTransportFactory
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.{WithRetryingHistory, Correlated}
import rhttpc.transport.amqp.AmqpConnectionFactory
import rhttpc.transport.{OutboundQueueData, PubSubTransport, Publisher}

import scala.concurrent.{ExecutionContext, Future}

case class ReliableHttpClientFactory(implicit actorSystem: ActorSystem) {
  import actorSystem.dispatcher

  def withOwnAmqpConnection(batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                            queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix): Future[ReliableHttpClient] = {
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { connection =>
      val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      ReliableClientFactory().create(transport, batchSize, queuesPrefix, {
        recovered(connection.close(), "closing amqp connection")
        Future.successful(Unit)
      })
    }
  }

  def create(connection: Connection,
             batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
             queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix): ReliableHttpClient = {
    val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    ReliableClientFactory().create(transport, batchSize, queuesPrefix)
  }

}