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
package rhttpc.sample

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import rhttpc.client.subscription._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class InMemDelayedEchoClient(delay: FiniteDuration)(implicit system: ActorSystem) extends DelayedEchoClient {
  import system.dispatcher

  private val subOnMsg: collection.concurrent.Map[SubscriptionOnResponse, String] = collection.concurrent.TrieMap()

  val subscriptionManager: SubscriptionManager =
    new SubscriptionManager {

      override def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit = {
        system.scheduler.scheduleOnce(delay) {
          subOnMsg.remove(subscription).foreach { msg =>
            consumer ! msg
          }
        }
      }

      override def start(): Unit = {}

      override def stop(): Future[Unit] = Future.unit
    }

  override def requestResponse(msg: String): ReplyFuture = {
    val uniqueSubOnResponse = SubscriptionOnResponse(UUID.randomUUID().toString)
    subOnMsg.put(uniqueSubOnResponse, msg)
    new ReplyFuture {
      override def pipeTo(listener: PublicationListener)
                         (implicit ec: ExecutionContext): Unit = {
        listener.subscriptionPromiseRegistered(uniqueSubOnResponse)
        listener.self ! RequestPublished(uniqueSubOnResponse)
      }

      override def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any] = ???
    }
  }
}