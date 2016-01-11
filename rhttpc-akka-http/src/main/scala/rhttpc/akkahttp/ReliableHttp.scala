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
import rhttpc.client.protocol.{WithRetryingHistory, Correlated}
import rhttpc.transport.amqp.AmqpConnectionFactory
import rhttpc.transport.{OutboundQueueData, PubSubTransport, Publisher}

import scala.concurrent.{ExecutionContext, Future}

object ReliableHttp {
  def apply()(implicit actorSystem: ActorSystem): Future[ReliableClient[HttpRequest]] = {
    import actorSystem.dispatcher
    val connectionF = AmqpConnectionFactory.connect(actorSystem)
    connectionF.map { connection =>
      implicit val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
      val subMgr = SubscriptionManager()
      new ReliableClient[HttpRequest](subMgr, requestPublisher) {
        override def close()(implicit ec: ExecutionContext): Future[Unit] = {
          recovered(super.close(), "closing ReliableHttp").map { _ =>
            connection.close()
          }
        }
      }
    }
  }

  def apply(connection: Connection)(implicit actorSystem: ActorSystem): ReliableClient[HttpRequest] = {
    implicit val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
    val subMgr = SubscriptionManager()
    new ReliableClient[HttpRequest](subMgr, requestPublisher)
  }

//  def publisher(connection: Connection, _isSuccess: PartialFunction[Try[HttpResponse], Unit])
//               (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableClient[HttpRequest] = {
//    val processor = new AcknowledgingMatchingSuccessResponseProcessor with HttpSuccessRecognizer {
//      override protected def isSuccess: PartialFunction[Try[HttpResponse], Unit] = _isSuccess
//    }
//    withEmbeddedProxy(connection, new EveryResponseHandler(processor))
//  }
//
//  def publisher(implicit actorSystem: ActorSystem, materialize: Materializer): Future[ReliableClient[HttpRequest]] = {
//    val processor = AcknowledgingSuccessStatusInResponseProcessor
//    withEmbeddedProxy(new EveryResponseHandler(processor))
//  }
//
//  def publisher(_isSuccess: PartialFunction[Try[HttpResponse], Unit])
//               (implicit actorSystem: ActorSystem, materialize: Materializer): Future[ReliableClient[HttpRequest]] = {
//    val processor = new AcknowledgingMatchingSuccessResponseProcessor with HttpSuccessRecognizer {
//      override protected def isSuccess: PartialFunction[Try[HttpResponse], Unit] = _isSuccess
//    }
//    withEmbeddedProxy(new EveryResponseHandler(processor))
//  }
//
//  def withEmbeddedProxy(connection: Connection, responseHandler: HttpResponseHandler)
//                       (implicit actorSystem: ActorSystem, materialize: Materializer): ReliableClient[HttpRequest] = {
//    val batchSize = actorSystem.settings.config.getInt("rhttpc.batchSize")
//    val proxy = ReliableHttpProxy(connection, responseHandler, batchSize)
//    proxy.run()
//    implicit val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
//    val subMgr = SubscriptionManager()
//    new ReliableClient[HttpRequest](subMgr, requestPublisher) {
//      override def close()(implicit ec: ExecutionContext): Future[Unit] = {
//        for {
//          _ <- recovered(super.close(), "closing ReliableHttp")
//          proxyCloseResult <- proxy.close()
//        } yield proxyCloseResult
//      }
//    }
//  }
//
//  def withEmbeddedProxy(responseHandler: HttpResponseHandler)
//                       (implicit actorSystem: ActorSystem, materialize: Materializer): Future[ReliableClient[HttpRequest]] = {
//    import actorSystem.dispatcher
//    val connectionF = AmqpConnectionFactory.connect(actorSystem)
//    connectionF.map { case connection =>
//      val batchSize = actorSystem.settings.config.getInt("rhttpc.batchSize")
//      val proxy = ReliableHttpProxy(connection, responseHandler, batchSize)
//      proxy.run()
//      implicit val transport = AmqpJson4sHttpTransportFactory.createRequestResponseTransport(connection)
//      val subMgr = SubscriptionManager()
//      new ReliableClient[HttpRequest](subMgr, requestPublisher) {
//        override def close()(implicit ec: ExecutionContext): Future[Unit] = {
//          for {
//            _ <- recovered(super.close(), "closing ReliableHttp")
//            _ <- recovered(proxy.close(), "closing ReliableHttpProxy")
//          } yield connection.close()
//        }
//      }
//    }
//  }

  private def requestPublisher(implicit transport: PubSubTransport[WithRetryingHistory[Correlated[HttpRequest]], _],
                               actorSystem: ActorSystem): Publisher[WithRetryingHistory[Correlated[HttpRequest]]] = {
    val requestQueueName = actorSystem.settings.config.getString("rhttpc.request-queue.name")
    transport.publisher(OutboundQueueData(requestQueueName))
  }
}