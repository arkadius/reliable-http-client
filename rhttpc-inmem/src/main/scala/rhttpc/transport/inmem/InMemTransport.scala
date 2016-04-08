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
package rhttpc.transport.inmem

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import rhttpc.transport.{InboundQueueData, Publisher, _}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration

private[inmem] class InMemTransport(transportActor: ActorRef) // TODO: stopping of transports / actors
                                   (createTimeout: FiniteDuration,
                                    stopConsumingTimeout: FiniteDuration,
                                    stopTimeout: FiniteDuration)
                                   (implicit system: ActorSystem) extends PubSubTransport with WithInstantPublisher with WithDelayedPublisher {

  import system.dispatcher

  override def publisher[PubMsg <: AnyRef](queueData: OutboundQueueData): Publisher[PubMsg] = {
    val queueActor = getOrCreateQueueActor(queueData.name)
    new InMemPublisher[PubMsg](queueActor)
  }

  override def subscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val queueActor = getOrCreateQueueActor(queueData.name)
    new InMemSubscriber[SubMsg](queueActor, consumer, fullMessage = false)(stopConsumingTimeout)
  }

  override def fullMessageSubscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val queueActor = getOrCreateQueueActor(queueData.name)
    new InMemSubscriber[SubMsg](queueActor, consumer, fullMessage = true)(stopConsumingTimeout)
  }

  private def getOrCreateQueueActor[SubMsg: Manifest](name: String): ActorRef = {
    implicit val timeout = Timeout(createTimeout)
    Await.result((transportActor ? GetOrCreateQueue(name)).mapTo[ActorRef], createTimeout)
  }

  override def stop(): Future[Unit] = gracefulStop(transportActor, stopTimeout).map(_ => Unit)
}

object InMemTransport {
  def apply(createTimeout: FiniteDuration = InMemDefaults.createTimeout,
            consumeTimeout: FiniteDuration = InMemDefaults.consumeTimeout,
            retryDelay: FiniteDuration = InMemDefaults.retryDelay,
            stopConsumingTimeout: FiniteDuration = InMemDefaults.stopConsumingTimeout,
            stopTimeout: FiniteDuration = InMemDefaults.stopTimeout)
           (implicit system: ActorSystem): PubSubTransport with WithInstantPublisher with WithDelayedPublisher = {
    val actor = system.actorOf(TransportActor.props(
      QueueActor.props(
        consumeTimeout = consumeTimeout,
        retryDelay = retryDelay
      )))
    new InMemTransport(actor)(
      createTimeout = createTimeout,
      stopConsumingTimeout = stopConsumingTimeout,
      stopTimeout = stopTimeout)
  }
}