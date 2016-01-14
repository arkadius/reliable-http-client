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
import akka.stream.Materializer
import com.rabbitmq.client.Connection
import rhttpc.akkahttp.amqp.AmqpJson4sHttpTransportFactory
import rhttpc.akkahttp.proxy.{AcceptSuccessHttpStatus, SuccessHttpResponseRecognizer, ReliableHttpProxyFactory}
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.proxy.FailureResponseHandleStrategyChooser
import rhttpc.transport.amqp.AmqpConnectionFactory

import scala.concurrent.Future

case class ReliableHttpClientFactory(implicit actorSystem: ActorSystem, materialize: Materializer) {
  import actorSystem.dispatcher

  def inOutWithSubscriptionsWithOwnAmqpConnection(successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                                                  batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                                                  queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                                                  retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): Future[InOutReliableHttpClient] = {
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { connection =>
      val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
      ReliableClientFactory().inOutWithSubscriptions(
        requestResponseTransport = requestResponseTransport,
        responseRequestTransport = responseRequestTransport,
        send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
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

  def inOutWithSubscriptions(connection: Connection,
                             successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                             batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                             queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix,
                             retryStrategy: FailureResponseHandleStrategyChooser = ConfigParser.parse(actorSystem).retryStrategy): InOutReliableHttpClient = {
    val requestResponseTransport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    val responseRequestTransport = AmqpJson4sHttpTransportFactory.createResponseRequestTransport(connection)
    ReliableClientFactory().inOutWithSubscriptions(
      requestResponseTransport = requestResponseTransport,
      responseRequestTransport = responseRequestTransport,
      send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }

}