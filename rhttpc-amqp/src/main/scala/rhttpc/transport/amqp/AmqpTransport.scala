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
import rhttpc.transport._

import scala.language.postfixOps

// TODO: actor-based, connection recovery
private[amqp] class AmqpTransport[PubMsg <: AnyRef, SubMsg](data: AmqpTransportCreateData[PubMsg, SubMsg]) extends PubSubTransport[PubMsg] {
  override def publisher(queueName: String): Publisher[PubMsg] = {
    val channel = data.connection.createChannel()
    channel.queueDeclare(queueName, true, false, false, null)
    val publisher = new AmqpPublisher(data, channel, queueName)
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber(queueName: String, consumer: ActorRef): Subscriber = {
    val channel = data.connection.createChannel()
    channel.basicQos(data.qos)
    channel.queueDeclare(queueName, true, false, false, null)
    new AmqpSubscriber(data, channel, queueName, consumer)
  }
}