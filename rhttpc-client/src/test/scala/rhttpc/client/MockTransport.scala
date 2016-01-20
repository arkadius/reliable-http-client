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
package rhttpc.client

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import rhttpc.client.protocol.{Correlated, Exchange, FailureExchange, SuccessExchange}
import rhttpc.transport._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

class MockTransport(awaitCond: (() => Boolean) => Unit)(implicit ec: ExecutionContext)
  extends PubSubTransport[Correlated[String], Correlated[Exchange[String, String]]] with WithDelayedPublisher {

  @volatile private var _publicationPromise: Promise[Unit] = _
  @volatile private var _replySubscriptionPromise: Promise[String] = _
  @volatile private var _ackOnReplySubscriptionFuture: Future[Any] = _
  @volatile private var consumer: ActorRef = _

  def publicationPromise: Promise[Unit] = {
    awaitCond(() => _publicationPromise != null)
    _publicationPromise
  }

  def replySubscriptionPromise: Promise[String] = {
    awaitCond(() => _replySubscriptionPromise != null)
    _replySubscriptionPromise
  }

  def ackOnReplySubscriptionFuture: Future[Any] = {
    awaitCond(() => _ackOnReplySubscriptionFuture != null)
    _ackOnReplySubscriptionFuture
  }

  override def publisher(data: OutboundQueueData): Publisher[Correlated[String]] =
    new Publisher[Correlated[String]] {
      override def publish(request: Message[Correlated[String]]): Future[Unit] = {
        _publicationPromise = Promise[Unit]()
        _replySubscriptionPromise = Promise[String]()
        implicit val timeout = Timeout(5 seconds)
        _replySubscriptionPromise.future.onComplete {
          case Success(result) =>
            _ackOnReplySubscriptionFuture = consumer ? Correlated(SuccessExchange(request.content.msg, result), request.content.correlationId)
          case Failure(ex) =>
            _ackOnReplySubscriptionFuture = consumer ? Correlated(FailureExchange(request.content.msg, ex), request.content.correlationId)
        }
        _publicationPromise.future
      }

      override def start(): Unit = {}

      override def stop(): Unit = {}
    }

  override def fullMessageSubscriber(data: InboundQueueData, consumer: ActorRef): Subscriber[Correlated[Exchange[String, String]]] =
    subscriber(data, consumer)

  override def subscriber(data: InboundQueueData, consumer: ActorRef): Subscriber[Correlated[Exchange[String, String]]] =
    new Subscriber[Correlated[Exchange[String, String]]] {
      MockTransport.this.consumer = consumer

      override def start(): Unit = {}

      override def stop(): Unit = {}
    }

}

object MockProxyTransport extends PubSubTransport[Correlated[Exchange[String, String]], Correlated[String]] with WithInstantPublisher {
  override def publisher(queueData: OutboundQueueData): Publisher[Correlated[Exchange[String, String]]] =
    new Publisher[Correlated[Exchange[String, String]]] {
      override def publish(msg: Message[Correlated[Exchange[String, String]]]): Future[Unit] = Future.successful(Unit)

      override def start(): Unit = {}

      override def stop(): Unit = {}
    }

  override def fullMessageSubscriber(data: InboundQueueData, consumer: ActorRef): Subscriber[Correlated[String]] =
    subscriber(data, consumer)

  override def subscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[Correlated[String]] =
    new Subscriber[Correlated[String]] {
      override def stop(): Unit = {}

      override def start(): Unit = {}
    }
}