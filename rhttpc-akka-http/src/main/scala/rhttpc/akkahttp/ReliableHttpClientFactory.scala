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
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.rabbitmq.client.Connection
import rhttpc.akkahttp.proxy.{AcceptSuccessHttpStatus, ReliableHttpProxyFactory, SuccessHttpResponseRecognizer}
import rhttpc.client.Recovered._
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.Correlated
import rhttpc.client.proxy.FailureResponseHandleStrategyChooser

import scala.concurrent.Future
import scala.util.Try

case class ReliableHttpClientFactory(implicit actorSystem: ActorSystem, materialize: Materializer) {
  import actorSystem.dispatcher
  import rhttpc.transport.amqp._
  import rhttpc.transport.json4s._
  
  private lazy val config = ConfigParser.parse(actorSystem)

  def inOutWithSubscriptions(connection: Connection,
                             successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                             batchSize: Int = config.batchSize,
                             queuesPrefix: String = config.queuesPrefix,
                             retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): InOutReliableHttpClient = {
    implicit val implicitConnection = connection
    ReliableClientFactory().inOutWithSubscriptions(
      send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }

  def inOut(connection: Connection,
            handleResponse: Correlated[Try[HttpResponse]] => Future[Unit],
            successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
            batchSize: Int = config.batchSize,
            queuesPrefix: String = config.queuesPrefix,
            retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): InOnlyReliableHttpClient = {
    implicit val implicitConnection = connection
    ReliableClientFactory().inOut(
      send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
      handleResponse = handleResponse,
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }

  def inOnly(connection: Connection,
             successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
             batchSize: Int = config.batchSize,
             queuesPrefix: String = config.queuesPrefix,
             retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): InOnlyReliableHttpClient = {
    implicit val implicitConnection = connection
    ReliableClientFactory().inOnly[HttpRequest](
      send = ReliableHttpProxyFactory.send(successRecognizer, batchSize)(_).map(_ => Unit),
      batchSize = batchSize,
      queuesPrefix = queuesPrefix,
      retryStrategy = retryStrategy
    )
  }

  def withOwnAmqpConnection = new {

    def inOutWithSubscriptions(successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
                               batchSize: Int = config.batchSize,
                               queuesPrefix: String = config.queuesPrefix,
                               retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): Future[InOutReliableHttpClient] = {
      val connectionF = AmqpConnectionFactory.connect(actorSystem)
      connectionF.map { implicit connection =>
        ReliableClientFactory().inOutWithSubscriptions(
          send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
          batchSize = batchSize,
          queuesPrefix = queuesPrefix,
          retryStrategy = retryStrategy,
          additionalStopAction = {
            recovered(connection.close(), "closing amqp connection")
            Future.successful(Unit)
          }
        )
      }
    }

    def inOut(handleResponse: Correlated[Try[HttpResponse]] => Future[Unit],
              successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
              batchSize: Int = config.batchSize,
              queuesPrefix: String = config.queuesPrefix,
              retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): Future[InOnlyReliableHttpClient] = {
      val connectionF = AmqpConnectionFactory.connect(actorSystem)
      connectionF.map { implicit connection =>
        ReliableClientFactory().inOut(
          send = ReliableHttpProxyFactory.send(successRecognizer, batchSize),
          handleResponse = handleResponse,
          batchSize = batchSize,
          queuesPrefix = queuesPrefix,
          retryStrategy = retryStrategy,
          additionalStopAction = {
            recovered(connection.close(), "closing amqp connection")
            Future.successful(Unit)
          }
        )
      }
    }

    def inOnly(successRecognizer: SuccessHttpResponseRecognizer = AcceptSuccessHttpStatus,
               batchSize: Int = config.batchSize,
               queuesPrefix: String = config.queuesPrefix,
               retryStrategy: FailureResponseHandleStrategyChooser = config.retryStrategy): Future[InOnlyReliableHttpClient] = {
      val connectionF = AmqpConnectionFactory.connect(actorSystem)
      connectionF.map { implicit connection =>
        ReliableClientFactory().inOnly[HttpRequest](
          send = ReliableHttpProxyFactory.send(successRecognizer, batchSize)(_).map(_ => Unit),
          batchSize = batchSize,
          queuesPrefix = queuesPrefix,
          retryStrategy = retryStrategy,
          additionalStopAction = {
            recovered(connection.close(), "closing amqp connection")
            Future.successful(Unit)
          }
        )
      }
    }

  }

}