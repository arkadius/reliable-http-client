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

import akka.actor.{ActorLogging, Actor, ActorRef, Status}
import akka.http.scaladsl.model.HttpResponse
import rhttpc.api.Correlated

class SubscriptionManagerActor extends Actor with ActorLogging {
  private var subscriptions: Map[SubscriptionOnResponse, ActorRef] = Map.empty

  override def receive: Actor.Receive = {
    case RegisterSubscription(sub, consumer) =>
      subscriptions += sub -> consumer
    case c@Correlated(msg: HttpResponse, correlationId) =>
      val sub = SubscriptionOnResponse(correlationId)
      subscriptions.get(sub) match {
        case Some(consumer) =>
          subscriptions -= sub
          consumer ! msg
        case None =>
          log.error(s"No subscription registered for $c. Will be skipped.")
          // FIXME: DLQ
      }
      sender() ! true //  ack
  }
}

case class RegisterSubscription(subscription: SubscriptionOnResponse, consumer: ActorRef)