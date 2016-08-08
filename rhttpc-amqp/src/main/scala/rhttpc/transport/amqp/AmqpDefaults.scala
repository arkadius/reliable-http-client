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

import com.rabbitmq.client.AMQP
import rhttpc.transport.SerializingPublisher.SerializedMessage
import rhttpc.transport.{Message, OutboundQueueData}

import scala.concurrent.duration._
import scala.language.postfixOps

object AmqpDefaults extends AmqpDefaults

trait AmqpDefaults
  extends AmqpQueuesNaming
  with AmqpExchangesNaming{

  import collection.convert.wrapAsJava._

  private[rhttpc] final val consumeTimeout: FiniteDuration = 5 minutes
  
  private[rhttpc] final val nackDelay: FiniteDuration = 10 seconds

  private[rhttpc] final val preparePersistentMessageProperties: PartialFunction[SerializedMessage, AMQP.BasicProperties] = {
    case SerializedMessage(_, additionalProps) =>
      persistentPropertiesBuilder.headers(additionalProps.mapValues {
        case b: BigInt => b.toLong.underlying()
        case other => other.asInstanceOf[AnyRef]
      }).build()
  }

  private def persistentPropertiesBuilder = new AMQP.BasicProperties.Builder()
    .deliveryMode(PERSISTENT_DELIVERY_MODE)

  private final val PERSISTENT_DELIVERY_MODE = 2

  private[rhttpc] def declarePublisherQueueWithDelayedExchangeIfNeed(data: AmqpDeclareOutboundQueueData) = {
    declareDlqAndBindToExchange(data)
    if (data.queueData.delayed) {
      val args = Map[String, AnyRef]("x-delayed-type" -> "direct")
      declareQueueAndBindToExchange(data, "x-delayed-message", args)
    } else if (data.exchangeName != "") {
      declareQueueAndBindToExchange(data, "direct", Map.empty)
    } else {
      declarePublisherQueue(data)
    }
  }

  private[rhttpc] def declareDlqAndBindToExchange(data: AmqpDeclareOutboundQueueData) = {
    val dlqData = AmqpDeclareOutboundQueueData(OutboundQueueData(prepareDlqQueueName(data.queueData.name)), dlqExchangeName, data.channel)
    declareQueueAndBindToExchange(dlqData, "direct", Map.empty)
  }


  private[rhttpc] def declareQueueAndBindToExchange(data: AmqpDeclareOutboundQueueData, exchangeType: String, args: Map[String, AnyRef]) = {
    import data._
    channel.exchangeDeclare(exchangeName, exchangeType, queueData.durability, queueData.autoDelete, args)
    val queueDeclareResult = declarePublisherQueue(data)
    channel.queueBind(queueData.name, exchangeName, queueData.name)
    queueDeclareResult
  }

  private[rhttpc] def declarePublisherQueue(data: AmqpDeclareOutboundQueueData) = {
    import data._
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, prepareDlqArgs(queueData.name))
  }

  private[rhttpc] def declareSubscriberQueue(data: AmqpDeclareInboundQueueData) = {
    import data._
    channel.basicQos(queueData.batchSize)
    channel.queueDeclare(queueData.name, queueData.durability, false, queueData.autoDelete, prepareDlqArgs(queueData.name))
  }

  private def prepareDlqArgs(queueName: String) =
    Map(
      "x-dead-letter-exchange" -> dlqExchangeName,
      "x-dead-letter-routing-key" -> prepareDlqQueueName(queueName)
    )

}