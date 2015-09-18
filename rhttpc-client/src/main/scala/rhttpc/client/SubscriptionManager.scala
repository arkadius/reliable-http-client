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

import akka.actor._
import akka.pattern._
import org.slf4j.LoggerFactory
import rhttpc.actor.impl._
import rhttpc.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Try

trait SubscriptionManager {
  def run(): Unit

  def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit

  def stop()(implicit ec: ExecutionContext): Future[Unit]
}

private[rhttpc] trait SubscriptionInternalManagement {
  def registerPromise(subscription: SubscriptionOnResponse): Unit

  def abort(subscription: SubscriptionOnResponse): Unit
}

object SubscriptionManager {
  private[client] def apply()(implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_]): SubscriptionManager with SubscriptionInternalManagement = {
    new SubscriptionManagerImpl()
  }
}

private[client] class SubscriptionManagerImpl (implicit actorFactory: ActorRefFactory, transport: PubSubTransport[_])
  extends SubscriptionManager with SubscriptionInternalManagement {

  private val log = LoggerFactory.getLogger(getClass)

  private val dispatcher = actorFactory.actorOf(Props[MessageDispatcherActor])

  private val transportSub = transport.subscriber("rhttpc-response", dispatcher)

  override def run(): Unit = {
    transportSub.run()
  }

  override def registerPromise(subscription: SubscriptionOnResponse): Unit = {
    dispatcher ! RegisterSubscriptionPromise(subscription)
  }

  override def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit = {
    dispatcher ! ConfirmOrRegisterSubscription(subscription, consumer)
  }

  override def abort(subscription: SubscriptionOnResponse): Unit = {
    dispatcher ! AbortSubscription(subscription)
  }

  override def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    Try(transportSub.stop()).recover {
      case ex => log.error("Exception while stopping subscriber", ex)
    }
    gracefulStop(dispatcher, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Graceful stop failed")
    )
  }
}

case class SubscriptionOnResponse(correlationId: String)