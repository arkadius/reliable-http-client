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
import akka.http.scaladsl.model.HttpResponse
import akka.pattern._
import akka.util.Timeout
import com.spingo.op_rabbit.consumer.Subscription
import rhttpc.api.Correlated
import rhttpc.api.json4s.Json4sSerializer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait SubscriptionManager {
  def initialized: Future[Unit]

  def register(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit

  def close(): Future[Unit]
}

object SubscriptionManager {
  def apply()(implicit actorFactory: ActorRefFactory, rabbitControlActor: RabbitControlActor): SubscriptionManager = {
    new SubscriptionManagerImpl()
  }
}

class SubscriptionManagerImpl(implicit actorFactory: ActorRefFactory, rabbitControlActor: RabbitControlActor) extends SubscriptionManager {
  import Json4sSerializer.formats
  import actorFactory.dispatcher
  import com.spingo.op_rabbit.Json4sSupport._

  private val subMgr = actorFactory.actorOf(Props[SubscriptionManagerActor], "subscription-manager")

  private val subscription = new Subscription {
    // A qos of 3 will cause up to 3 concurrent messages to be processed at any given time.
    def config = channel(qos = 3) {
      consume(queue("rhttpc-response")) {
        body(as[Correlated[HttpResponse]]) { response =>
          implicit val timeout = Timeout(10 seconds)
          ack(subMgr ? response)
        }
      }
    }
  }

  rabbitControlActor.rabbitControl ! subscription

  override def initialized: Future[Unit] = {
    subscription.initialized
  }

  override def register(subscription: SubscriptionOnResponse, consumer: ActorRef) = {
    subMgr ! RegisterSubscription(subscription, consumer)
  }

  override def close(): Future[Unit] = {
    subscription.close(30 seconds)
    subscription.closed
  }
}

case class SubscriptionOnResponse(correlationId: String)

trait SubscriptionsHolder { this: Actor =>

  protected def subscriptionManager: SubscriptionManager

  protected var subscriptions: Set[SubscriptionOnResponse] = Set.empty

  protected def registerSubscriptions(subs: Set[SubscriptionOnResponse]): Unit = {
    subscriptions ++= subs
    subscriptions.foreach(subscriptionManager.register(_, self))
  }

  private[rhttpc] def failedRequest(ex: Throwable, subscription: SubscriptionOnResponse) = {
    self ! Status.Failure(ex)
  }

  protected val handleRegisterSubscription: Receive = {
    case DoRegisterSubscription(subscription) =>
      subscriptions = subscriptions + subscription
      stateChanged()
      subscriptionManager.register(subscription, self)
  }

  protected val handleMessageFromSubscription: Receive = {
    case MessageFromSubscription(msg, subscription) =>
      subscriptions = subscriptions - subscription
      stateChanged()
      self ! msg
  }

  def stateChanged(): Unit
}

case class MessageFromSubscription(msg: Any, subscription: SubscriptionOnResponse)
