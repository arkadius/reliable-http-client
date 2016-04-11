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
package rhttpc.transport.fallback

import akka.actor.{ActorRef, ActorSystem, Scheduler}
import rhttpc.transport._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class FallbackTransport(main: PubSubTransport,
                        fallback: PubSubTransport)
                       (maxFailures: Int,
                        callTimeout: FiniteDuration,
                        resetTimeout: FiniteDuration)
                       (implicit system: ActorSystem) extends PubSubTransport with WithInstantPublisher with WithDelayedPublisher {

  import system.dispatcher

  override def publisher[PubMsg <: AnyRef](queueData: OutboundQueueData): Publisher[PubMsg] =
    new FallbackPublisher[PubMsg](
      main = main.publisher(queueData),
      fallback = fallback.publisher(queueData))(
      maxFailures = maxFailures,
      callTimeout = callTimeout,
      resetTimeout = resetTimeout
    )

  override def subscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    new SubscriberAggregate[SubMsg](Seq(
      main.subscriber(queueData, consumer),
      fallback.subscriber(queueData, consumer)
    ))

  override def fullMessageSubscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    new SubscriberAggregate[SubMsg](Seq(
      main.fullMessageSubscriber(queueData, consumer),
      fallback.fullMessageSubscriber(queueData, consumer)
    ))

  override def stop(): Future[Unit] = {
    import rhttpc.utils.Recovered._
    recoveredFuture("stopping main transport", main.stop())
      .flatMap(_ => recoveredFuture("stopping fallback transport", fallback.stop()))
  }

}