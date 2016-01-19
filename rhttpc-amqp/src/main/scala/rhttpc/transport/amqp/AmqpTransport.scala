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
import com.rabbitmq.client.{AMQP, Channel, Connection}
import rhttpc.transport._

import scala.language.postfixOps

trait AmqpTransport[PubMsg <: AnyRef, SubMsg] extends PubSubTransport[PubMsg, SubMsg] with WithInstantPublisher with WithDelayedPublisher

// TODO: actor-based, connection recovery
private[rhttpc] class AmqpTransportImpl[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                                                  exchangeName: String,
                                                                  serializer: Serializer[PubMsg],
                                                                  deserializer: Deserializer[SubMsg],
                                                                  declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk,
                                                                  declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk,
                                                                  prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties])
                                                                 (implicit actorSystem: ActorSystem) extends AmqpTransport[PubMsg, SubMsg] {

  import actorSystem.dispatcher

  override def publisher(queueData: OutboundQueueData): AmqpPublisher[PubMsg] = {
    val channel = connection.createChannel()
    declarePublisherQueue(AmqpDeclareOutboundQueueData(queueData, exchangeName, channel))
    val publisher = new AmqpPublisher[PubMsg](
      channel = channel,
      queueName = queueData.name,
      exchangeName = exchangeName,
      serializer = serializer,
      prepareProperties = prepareProperties
    )
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber(queueData: InboundQueueData, consumer: ActorRef): AmqpSubscriber[SubMsg] = {
    val channel = connection.createChannel()
    declareSubscriberQueue(AmqpDeclareInboundQueueData(queueData, channel))
    new AmqpSubscriber[SubMsg](
      channel,
      queueData.name,
      consumer,
      deserializer
    ) with SendingSimpleMessage[SubMsg]
  }

  override def fullMessageSubscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val channel = connection.createChannel()
    declareSubscriberQueue(AmqpDeclareInboundQueueData(queueData, channel))
    new AmqpSubscriber[SubMsg](
      channel,
      queueData.name,
      consumer,
      deserializer
    ) with SendingFullMessage[SubMsg]
  }
}

object AmqpTransport {
  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      exchangeName: String = "",
                                      declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk = AmqpDefaults.declarePublisherQueueWithDelayedExchangeIfNeed,
                                      declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk = AmqpDefaults.declareSubscriberQueue,
                                      prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = AmqpDefaults.preparePersistentMessageProperties)
                                     (implicit actorSystem: ActorSystem,
                                      serializer: Serializer[PubMsg],
                                      deserializer: Deserializer[SubMsg]): AmqpTransport[PubMsg, SubMsg] =
    new AmqpTransportImpl[PubMsg, SubMsg](
      connection = connection,
      exchangeName = exchangeName,
      serializer = serializer,
      deserializer = deserializer,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      prepareProperties = AmqpDefaults.preparePersistentMessageProperties
    )

}

case class AmqpDeclareInboundQueueData(queueData: InboundQueueData, channel: Channel)

case class AmqpDeclareOutboundQueueData(queueData: OutboundQueueData, exchangeName: String, channel: Channel)