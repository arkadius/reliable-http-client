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

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

trait AmqpTransport[PubMsg <: AnyRef, SubMsg] extends PubSubTransport[PubMsg, SubMsg, AmqpInboundQueueData, AmqpOutboundQueueData]

// TODO: actor-based, connection recovery
private[amqp] class AmqpTransportImpl[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                                                exchangeName: String,
                                                                serializer: Serializer[PubMsg],
                                                                deserializer: Deserializer[SubMsg],
                                                                ignoreInvalidMessages: Boolean,
                                                                declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk,
                                                                declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk,
                                                                prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties])
                                                               (implicit actorSystem: ActorSystem) extends AmqpTransport[PubMsg, SubMsg] {

  import actorSystem.dispatcher

  override def publisher(queueData: AmqpOutboundQueueData): AmqpPublisher[PubMsg] = {
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

  override def subscriber(queueData: AmqpInboundQueueData, consumer: ActorRef): AmqpSubscriber[SubMsg] = {
    val channel = connection.createChannel()
    declareSubscriberQueue(AmqpDeclareInboundQueueData(queueData, channel))
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
                                      declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk = defaultDeclarePublisherQueue,
                                      declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk = defaultDeclareSubscriberQueue,
                                      prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = defaultPreparePublishProperties)
                                     (implicit actorSystem: ActorSystem): AmqpTransport[PubMsg, SubMsg] =
    new AmqpTransportImpl[PubMsg, SubMsg](
      connection = connection,
      exchangeName = exchangeName,
      serializer = serializer,
      deserializer = deserializer,
      ignoreInvalidMessages = ignoreInvalidMessages,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      prepareProperties = defaultPreparePublishProperties
    )

  import collection.convert.wrapAsJava._

  final val defaultPreparePublishProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = {
    case InstantMessage(_) =>
      persistentPropertiesBuilder.build()
    case InstantMessageWithSpecifiedProperties(_, props) =>
      persistentPropertiesBuilder.headers(props.asInstanceOf[Map[String, AnyRef]]).build()
    case DelayedMessage(_, delay) =>
      val headers = delayHeaders(delay)
      persistentPropertiesBuilder.headers(headers.asInstanceOf[Map[String, AnyRef]]).build()
    case DelayedMessageWithSpecifiedProperties(_, delay, props) =>
      val headers = delayHeaders(delay) ++ props
      persistentPropertiesBuilder.headers(props.asInstanceOf[Map[String, AnyRef]]).build()
  }

  private def persistentPropertiesBuilder = new AMQP.BasicProperties.Builder()
    .deliveryMode(PERSISTENT_DELIVERY_MODE)

  private final val PERSISTENT_DELIVERY_MODE = 2

  private def delayHeaders(delay: FiniteDuration): Map[String, Any] =
    Map("x-delay" -> delay.toMillis)

  private final def defaultDeclarePublisherQueue(data: AmqpDeclareOutboundQueueData) = {
    import data._
    if (queueData.delayed) {
      val args = Map[String, AnyRef]("x-delayed-type" -> "direct")
      channel.exchangeDeclare(exchangeName, "x-delayed-message", queueData.durability, queueData.autoDelete, args)
      channel.queueBind(queueData.name, exchangeName, queueData.name)
    }
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, null)
  }

  private final def defaultDeclareSubscriberQueue(data: AmqpDeclareInboundQueueData) = {
    import data._
    channel.basicQos(queueData.qos)
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, null)
  }
}

case class AmqpDeclareInboundQueueData(queueData: AmqpInboundQueueData, channel: Channel)

case class AmqpDeclareOutboundQueueData(queueData: AmqpOutboundQueueData, exchangeName: String, channel: Channel)

case class AmqpInboundQueueData(name: String, qos: Int, durability: Boolean = true, autoDelete: Boolean = false)

case class AmqpOutboundQueueData(name: String, durability: Boolean = true, autoDelete: Boolean = false, delayed: Boolean = false)