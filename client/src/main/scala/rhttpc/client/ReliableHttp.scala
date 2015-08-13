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

import java.util.UUID

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.HttpRequest
import akka.util.Timeout
import org.slf4j.LoggerFactory
import rhttpc.api.Correlated
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class ReliableHttp(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[Correlated[HttpRequest], _]) {
  private lazy val log = LoggerFactory.getLogger(getClass)

  private val publisher = transport.publisher("rhttpc-request")

  def send(request: HttpRequest)(implicit ec: ExecutionContext): Future[DoRegisterSubscription] = {
    val correlationId = UUID.randomUUID().toString
    implicit val timeout = Timeout(10 seconds)
    val correlated = Correlated(request, correlationId)
    publisher.publish(correlated).map { _ =>
      log.debug(s"Request: $correlated successfully acknowledged")
      // FIXME request is acknowledged and we are not sure if subscription will be registered before response will come
      // FIXME register subscription declaration and then subscription confirmation or abortion
      DoRegisterSubscription(SubscriptionOnResponse(correlationId))
    }
  }
}

object ReliableHttp {
  def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[Correlated[HttpRequest], _]) = new ReliableHttp()
}

case class DoRegisterSubscription(subscription: SubscriptionOnResponse)

class NoAckException(request: HttpRequest) extends Exception(s"No acknowledge for request: $request")