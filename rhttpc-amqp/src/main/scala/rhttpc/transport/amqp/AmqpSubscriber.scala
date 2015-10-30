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
import akka.pattern._
import akka.util.Timeout
import com.rabbitmq.client._
import rhttpc.transport._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

private[amqp] class AmqpSubscriber[Sub](data: AmqpTransportCreateData[_, Sub],
                                                 channel: Channel,
                                                 queueName: String,
                                                 consumer: ActorRef)
                                                (implicit deserializer: Deserializer[Sub])
  extends Subscriber[Sub] {

  import data.executionContext

  override def run(): Unit = {
    val queueConsumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
        val msg = deserializer.deserialize(new String(body, "UTF-8"))
        implicit val timeout = Timeout(5 minute)
        (consumer ? msg).onComplete {
          case Success(_) => channel.basicAck(envelope.getDeliveryTag, false)
          case Failure(_) if data.ackOnMessageFailure => channel.basicAck(envelope.getDeliveryTag, false)
          case Failure(_) => channel.basicNack(envelope.getDeliveryTag, false, true)
        }
      }
    }
    channel.basicConsume(queueName, false, queueConsumer)
  }

  override def stop(): Unit = {
    channel.close()
  }

}