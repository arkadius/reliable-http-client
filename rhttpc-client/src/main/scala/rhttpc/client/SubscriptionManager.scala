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
import akka.util.Timeout
import rhttpc.actor.impl.{AbortSubscription, ConfirmOrRegisterSubscription, MessageDispatcherActor, RegisterSubscriptionPromise}
import rhttpc.api.transport.PubSubTransport

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait SubscriptionManager {
  def run(): Unit

  def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit

  def stop()(implicit ec: ExecutionContext): Future[Unit]
}

private[client] trait SubscriptionInternalManagement {
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

  private val dispatcher = actorFactory.actorOf(Props[MessageDispatcherActor], "subscription-manager")

  private val transportSub = transport.subscriber("rhttpc-response", dispatcher)

  override def run(): Unit = {
    transportSub.run()
  }

  override def registerPromise(subscription: SubscriptionOnResponse): Unit = {
    dispatcher ! RegisterSubscriptionPromise(subscription)
  }

  override def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit = {
    implicit val timeout = Timeout(30 seconds)
    dispatcher ! ConfirmOrRegisterSubscription(subscription, consumer)
  }

  override def abort(subscription: SubscriptionOnResponse): Unit = {
    dispatcher ! AbortSubscription(subscription)
  }

  override def stop()(implicit ec: ExecutionContext): Future[Unit] = {
    transportSub.stop()
    gracefulStop(dispatcher, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Subscription manager hasn't been stopped correctly")
    )
  }
}

case class SubscriptionOnResponse(correlationId: String)