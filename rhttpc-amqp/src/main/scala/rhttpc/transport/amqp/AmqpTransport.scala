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
import com.rabbitmq.client.AMQP.Queue
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{Connection, AMQP, Channel}
import rhttpc.transport._

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

trait AmqpTransport[PubMsg <: AnyRef, SubMsg] extends PubSubTransport[PubMsg, SubMsg]

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

  final val defaultPreparePublishProperties: PartialFunction[Message[Any], AMQP.BasicProperties] =
    handleMessageWithSpecificProperties orElse handleMessage(Map.empty)

  private lazy val handleMessageWithSpecificProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = {
    case MessageWithSpecifiedProperties(underlying, props) =>
      handleMessage(props)(underlying)
  }

  private def handleMessage(additionalProps: Map[String, Any]): PartialFunction[Message[Any], AMQP.BasicProperties] = {
    case InstantMessage(_) =>
      persistentPropertiesBuilder.build()
    case DelayedMessage(_, delay) =>
      val headers = delayHeaders(delay) ++ additionalProps
      persistentPropertiesBuilder.headers(headers.asInstanceOf[Map[String, AnyRef]]).build()
  }

  private def persistentPropertiesBuilder = new AMQP.BasicProperties.Builder()
    .deliveryMode(PERSISTENT_DELIVERY_MODE)

  private final val PERSISTENT_DELIVERY_MODE = 2

  private def delayHeaders(delay: FiniteDuration): Map[String, Any] =
    Map("x-delay" -> delay.toMillis)

  private def defaultDeclarePublisherQueue(data: AmqpDeclareOutboundQueueData) = {
    import data._
    val dlqData = AmqpDeclareOutboundQueueData(OutboundQueueData(prepareDlqName(queueData.name)), DLQ_EXCHANGE_NAME, channel)
    declareQueueAndBindToExchange(dlqData, "direct", Map.empty)
    if (queueData.delayed) {
      val args = Map[String, AnyRef]("x-delayed-type" -> "direct")
      declareQueueAndBindToExchange(data, "x-delayed-message", args)
    } else if (exchangeName != "") {
      declareQueueAndBindToExchange(data, "direct", Map.empty)
    } else {
      declarePublisherQueue(data)
    }
  }

  private def declareQueueAndBindToExchange(data: AmqpDeclareOutboundQueueData, exchangeType: String, args: Map[String, AnyRef]) = {
    import data._
    channel.exchangeDeclare(exchangeName, exchangeType, queueData.durability, queueData.autoDelete, args)
    val queueDeclareResult = declarePublisherQueue(data)
    channel.queueBind(queueData.name, exchangeName, queueData.name)
    queueDeclareResult
  }

  private def declarePublisherQueue(data: AmqpDeclareOutboundQueueData) = {
    import data._
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, prepareDlqArgs(queueData.name))
  }

  private def defaultDeclareSubscriberQueue(data: AmqpDeclareInboundQueueData) = {
    import data._
    channel.basicQos(queueData.batchSize)
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, prepareDlqArgs(queueData.name))
  }

  private def prepareDlqArgs(queueName: String) =
    Map(
      "x-dead-letter-exchange" -> DLQ_EXCHANGE_NAME,
      "x-dead-letter-routing-key" -> prepareDlqName(queueName)
    )

  private final val DLQ_EXCHANGE_NAME = "dlq"

  private def prepareDlqName(queueName: String) = queueName + ".dlq"
}

case class AmqpDeclareInboundQueueData(queueData: InboundQueueData, channel: Channel)

case class AmqpDeclareOutboundQueueData(queueData: OutboundQueueData, exchangeName: String, channel: Channel)