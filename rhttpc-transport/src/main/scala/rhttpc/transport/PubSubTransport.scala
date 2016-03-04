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
package rhttpc.transport

import akka.actor.ActorRef

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, postfixOps}
import scala.util.Try

trait PubSubTransport {
  def publisher[PubMsg <: AnyRef](queueName: String): Publisher[PubMsg] =
    publisher(OutboundQueueData(queueName))

  def subscriber[SubMsg: Manifest](queueName: String, consumer: ActorRef): Subscriber[SubMsg] =
    subscriber(InboundQueueData(queueName, batchSize = 10), consumer)

  def publisher[PubMsg <: AnyRef](queueData: OutboundQueueData): Publisher[PubMsg]

  def subscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg]

  def fullMessageSubscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg]

}

trait WithInstantPublisher { self: PubSubTransport =>
}

trait WithDelayedPublisher { self: PubSubTransport =>
}

case class InboundQueueData(name: String, batchSize: Int, parallelConsumers: Int = 1, durability: Boolean = true, autoDelete: Boolean = false)

case class OutboundQueueData(name: String, durability: Boolean = true, autoDelete: Boolean = false, delayed: Boolean = false)

trait Publisher[-Msg] {

  final def publish(msg: Msg): Future[Unit] =
    publish(Message(msg))

  def publish(msg: Message[Msg]): Future[Unit]

  def start(): Unit

  def stop(): Future[Unit]

}

trait Subscriber[+SubMsg] {

  def start(): Unit

  def stop(): Future[Unit]

}

class SubscriberAggregate[SubMsg](subscribers: Seq[Subscriber[SubMsg]])
                                 (implicit ec: ExecutionContext) extends Subscriber[SubMsg] {
  override def start(): Unit = {
    subscribers.foreach(_.start())
  }

  override def stop(): Future[Unit] = {
    Future.sequence(subscribers.map(_.stop())).map(_ => Unit)
  }
}

trait RejectingMessage { self: Exception =>
}

trait Serializer {
  def serialize[Msg <: AnyRef](obj: Msg): String
}

trait Deserializer {
  def deserialize[Msg: Manifest](value: String): Try[Msg]
}