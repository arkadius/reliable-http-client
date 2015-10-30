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
import com.rabbitmq.client.{Channel, ShutdownListener, ShutdownSignalException}
import rhttpc.transport._

import scala.language.postfixOps

trait AmqpTransport[PubMsg <: AnyRef, SubMsg <: AnyRef] extends PubSubTransport[PubMsg, SubMsg, AmqpInboundQueueData, AmqpOutboundQueueData]

// TODO: actor-based, connection recovery
private[amqp] class AmqpTransportImpl[PubMsg <: AnyRef, SubMsg <: AnyRef](data: AmqpTransportCreateData[PubMsg, SubMsg],
                                                                          declarePublisherQueue: (AmqpOutboundQueueData, Channel) => DeclareOk,
                                                                          declareSubscriberQueue: (AmqpInboundQueueData, Channel) => DeclareOk)
  extends AmqpTransport[PubMsg, SubMsg] {

  override def publisher(queueData: AmqpOutboundQueueData): Publisher[PubMsg] = {
    val channel = data.connection.createChannel()
    declarePublisherQueue(queueData, channel)
    implicit val serializer = data.serializer
    val publisher = new AmqpPublisher(data, channel, queueData.name)
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber(queueData: AmqpInboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val channel = data.connection.createChannel()
    declareSubscriberQueue(queueData, channel)
    implicit val deserializer = data.deserializer
    new AmqpSubscriber(data, channel, queueData.name, consumer)
  }

  override def close(onShutdownAction: => Unit): Unit = {
    data.connection.addShutdownListener(new ShutdownListener {
      override def shutdownCompleted(e: ShutdownSignalException): Unit = onShutdownAction
    })
    data.connection.close()
  }

}