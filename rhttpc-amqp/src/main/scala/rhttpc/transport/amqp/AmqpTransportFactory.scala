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

import com.rabbitmq.client._
import org.json4s.Formats
import rhttpc.transport._

import scala.concurrent.ExecutionContext

trait AmqpTransportFactory extends PubSubTransportFactory {
  override type DataT[P, S] = AmqpTransportCreateData[P, S]

  private final val DELAYED_EXCHANGE_NAME = "delayed"

  protected def declarePublisherQueue(in: AmqpQueueCreateData): Unit = {
    in.channel.queueDeclare(in.queueName, true, false, false, null) // using default exchange
  }

  protected def declareAndBindDelayedExchangeForPublisherQueue(in: AmqpQueueCreateData): String = {
    import collection.convert.wrapAsJava._
    val props = Map("x-delayed-type" -> "direct")
    in.channel.exchangeDeclare(DELAYED_EXCHANGE_NAME, "x-delayed-message", true, false, props)
    in.channel.queueBind(in.queueName, DELAYED_EXCHANGE_NAME, in.queueName)
    DELAYED_EXCHANGE_NAME
  }

  protected def declareSubscriberQueue(in: AmqpQueueCreateData): Unit = {
    in.channel.basicQos(10)
    in.channel.queueDeclare(in.queueName, true, false, false, null) // using default exchange
  }

  override def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): PubSubTransport[PubMsg] = {
    new AmqpTransport[PubMsg, SubMsg](
      data = data,
      declarePublisherQueue = declarePublisherQueue,
      declareAndBindDelayedExchangeForPublisherQueue = declareAndBindDelayedExchangeForPublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue)
  }

}

object AmqpTransportFactory extends AmqpTransportFactory

case class AmqpTransportCreateData[PubMsg, SubMsg](connection: Connection)
                                                  (implicit val executionContext: ExecutionContext,
                                                   val subMsgManifest: Manifest[SubMsg],
                                                   val formats: Formats) extends TransportCreateData[PubMsg, SubMsg]

case class AmqpQueueCreateData(channel: Channel, queueName: String)