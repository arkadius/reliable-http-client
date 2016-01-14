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
package rhttpc.client.subscription

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import rhttpc.client._
import rhttpc.client.protocol.Correlated

import scala.util.{Failure, Success, Try}

private[subscription] class MessageDispatcherActor extends Actor with ActorLogging {

  private var promisesOnPending: Map[SubscriptionOnResponse, Option[PendingMessage]] = Map.empty

  private var subscriptions: Map[SubscriptionOnResponse, ActorRef] = Map.empty

  override def receive: Actor.Receive = {
    case RegisterSubscriptionPromise(sub) =>
      log.debug(s"Registering subscription promise: $sub")
      promisesOnPending += sub -> None
    case ConfirmOrRegisterSubscription(sub, consumer) =>
      promisesOnPending.get(sub).foreach { pending =>
        if (pending.nonEmpty) {
          log.debug(s"Confirming subscription: $sub. Sending outstanding messages: ${pending.size}.")
          pending.foreach { pending =>
            consumer.tell(MessageFromSubscription(pending.msg, sub), pending.sender)
          }
        } else {
          log.debug(s"Confirming subscription: $sub")
        }
        promisesOnPending -= sub
      }
      subscriptions += sub -> consumer
    case AbortSubscription(sub) =>
      promisesOnPending.get(sub) match {
        case Some(pending) if pending.isEmpty =>
          log.debug(s"Aborted subscription: $sub.")
          promisesOnPending -= sub
        case Some(pending) =>
          log.error(s"Aborted subscription: $sub. There were pending messages: ${pending.size}.")
          promisesOnPending -= sub
        case None =>
          log.warning(s"Confirmed subscription promise: $sub was missing")
      }
    case Correlated(msg: Try[_], correlationId) =>
      val sub = SubscriptionOnResponse(correlationId)
      val underlyingOrFailure = msg match {
        case Success(underlying) => underlying
        case Failure(ex) => Status.Failure(ex)
      }
      (subscriptions.get(sub), promisesOnPending.get(sub)) match {
        case (Some(consumer), optionalPending) =>
          optionalPending.foreach { pending =>
            log.error(s"There were both registered subscription and subscription promise with pending messages: ${pending.size}.")
          }
          log.debug(s"Consuming message: $correlationId")
          subscriptions -= sub
          consumer forward MessageFromSubscription(underlyingOrFailure, sub) // consumer should ack
        case (None, Some(None)) =>
          log.debug(s"Adding pending message: $correlationId")
          promisesOnPending = promisesOnPending.updated(sub, Some(PendingMessage(underlyingOrFailure)))
        case (None, Some(Some(pending))) =>
          log.error(s"There already was pending message: $pending for subscription. Overriding it.")
          pending.ack()
          promisesOnPending = promisesOnPending.updated(sub, Some(PendingMessage(underlyingOrFailure)))
        case (None, None) =>
          log.error(s"No subscription (promise) registered for $correlationId. Will be skipped.")
          // TODO: DLQ
          sender() ! Unit //  ack
      }
  }

  class PendingMessage private (val msg: Any, val sender: ActorRef) {
    def ack() = sender ! Unit
  }

  object PendingMessage {
    def apply(msg: Any): PendingMessage = new PendingMessage(msg, sender())
  }
}

private[subscription] case class RegisterSubscriptionPromise(sub: SubscriptionOnResponse)

private[subscription] case class ConfirmOrRegisterSubscription(sub: SubscriptionOnResponse, consumer: ActorRef)

private[subscription] case class AbortSubscription(sub: SubscriptionOnResponse)