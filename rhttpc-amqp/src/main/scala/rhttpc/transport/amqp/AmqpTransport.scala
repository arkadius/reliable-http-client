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

import akka.actor._
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{Connection, AMQP, Channel}
import rhttpc.transport._

import scala.language.postfixOps

trait AmqpTransport[PubMsg <: AnyRef, SubMsg] extends PubSubTransport[PubMsg, SubMsg, AmqpInboundQueueData, AmqpOutboundQueueData, AMQP.BasicProperties]

// TODO: actor-based, connection recovery
private[amqp] class AmqpTransportImpl[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                                                exchangeName: String = "",
                                                                serializer: Serializer[PubMsg],
                                                                deserializer: Deserializer[SubMsg],
                                                                ignoreInvalidMessages: Boolean,
                                                                declarePublisherQueue: (AmqpOutboundQueueData, Channel) => DeclareOk,
                                                                declareSubscriberQueue: (AmqpInboundQueueData, Channel) => DeclareOk,
                                                                defaultPublishProperties: AMQP.BasicProperties)
                                                               (implicit actorSystem: ActorSystem) extends AmqpTransport[PubMsg, SubMsg] {

  import actorSystem.dispatcher

  override def publisher(queueData: AmqpOutboundQueueData): AmqpPublisher[PubMsg] = {
    val channel = connection.createChannel()
    declarePublisherQueue(queueData, channel)
    val publisher = new AmqpPublisher[PubMsg](
      channel = channel,
      queueName = queueData.name,
      exchangeName = exchangeName,
      serializer = serializer,
      defaultPublishProperties = defaultPublishProperties
    )
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber(queueData: AmqpInboundQueueData, consumer: ActorRef): AmqpSubscriber[SubMsg] = {
    val channel = connection.createChannel()
    declareSubscriberQueue(queueData, channel)
    new AmqpSubscriber(
      channel,
      queueData.name,
      consumer,
      deserializer,
      ignoreInvalidMessages
    )
  }

}

object AmqpTransport {
  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      exchangeName: String = "",
                                      serializer: Serializer[PubMsg],
                                      deserializer: Deserializer[SubMsg],
                                      ignoreInvalidMessages: Boolean = true,
                                      declarePublisherQueue: (AmqpOutboundQueueData, Channel) => DeclareOk = defaultDeclarePublisherQueue,
                                      declareSubscriberQueue: (AmqpInboundQueueData, Channel) => DeclareOk = defaultDeclareSubscriberQueue,
                                      defaultPublishProperties: AMQP.BasicProperties = DEFAULT_PUBLISH_PROPERTIES)
                                     (implicit actorSystem: ActorSystem): AmqpTransport[PubMsg, SubMsg] =
    new AmqpTransportImpl[PubMsg, SubMsg](
      connection = connection,
      exchangeName = exchangeName,
      serializer = serializer,
      deserializer = deserializer,
      ignoreInvalidMessages = ignoreInvalidMessages,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      defaultPublishProperties = defaultPublishProperties
    )

  private final def DEFAULT_PUBLISH_PROPERTIES: AMQP.BasicProperties =
    new AMQP.BasicProperties.Builder()
      .deliveryMode(PERSISTENT_DELIVERY_MODE)
      .build()

  private final val PERSISTENT_DELIVERY_MODE = 2

  private final def defaultDeclarePublisherQueue(in: AmqpOutboundQueueData, channel: Channel) = {
    channel.queueDeclare(in.name, in.durability, false, in.autoDelete, null) // using default exchange
  }

  private final def defaultDeclareSubscriberQueue(in: AmqpInboundQueueData, channel: Channel) = {
    channel.basicQos(in.qos)
    channel.queueDeclare(in.name, in.durability, false, in.autoDelete, null) // using default exchange
  }
}

case class AmqpInboundQueueData(name: String, qos: Int, durability: Boolean = true, autoDelete: Boolean = false)

case class AmqpOutboundQueueData(name: String, durability: Boolean = true, autoDelete: Boolean = false)