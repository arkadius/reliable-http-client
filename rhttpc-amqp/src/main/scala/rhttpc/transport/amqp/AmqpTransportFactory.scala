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
package rhttpc.transport.amqp

import akka.actor.ActorSystem
import com.rabbitmq.client._
import rhttpc.transport._

import scala.concurrent.ExecutionContext

trait AmqpTransportFactory extends PubSubTransportFactory {
  override type DataT[P, S] = AmqpTransportCreateData[P, S]
  override type InboundQueueDataT = AmqpInboundQueueData
  override type OutboundQueueDataT = AmqpOutboundQueueData

  protected def declarePublisherQueue(in: AmqpOutboundQueueData, channel: Channel) = {
    channel.queueDeclare(in.name, in.durability, false, in.autoDelete, null) // using default exchange
  }

  protected def declareSubscriberQueue(in: AmqpInboundQueueData, channel: Channel) = {
    channel.basicQos(in.qos)
    channel.queueDeclare(in.name, in.durability, false, in.autoDelete, null) // using default exchange
  }

  override def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): AmqpTransport[PubMsg, SubMsg] = {
    new AmqpTransportImpl[PubMsg, SubMsg](
      data = data,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue
    )
  }

}

object AmqpTransportFactory extends AmqpTransportFactory

case class AmqpTransportCreateData[PubMsg, SubMsg](connection: Connection,
                                                   exchangeName: String = "",
                                                   serializer: Serializer[PubMsg],
                                                   deserializer: Deserializer[SubMsg],
                                                   ignoreInvalidMessages: Boolean = true)
                                                  (implicit val actorSystem: ActorSystem) extends TransportCreateData[PubMsg, SubMsg] {
  implicit def executionContext: ExecutionContext = actorSystem.dispatcher
}

case class AmqpInboundQueueData(name: String, qos: Int, durability: Boolean = true, autoDelete: Boolean = false) extends QueueData

case class AmqpOutboundQueueData(name: String, durability: Boolean = true, autoDelete: Boolean = false) extends QueueData
