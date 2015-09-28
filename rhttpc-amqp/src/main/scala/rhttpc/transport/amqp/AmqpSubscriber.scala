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

import java.io._

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import com.rabbitmq.client._
import org.json4s.native._
import rhttpc.transport._
import rhttpc.transport.api.Subscriber

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

private[amqp] class AmqpSubscriber[Sub](data: AmqpTransportCreateData[_, Sub],
                                        channel: Channel,
                                        queueName: String,
                                        consumer: ActorRef) extends Subscriber {
  import data.actorSystem.dispatcher

  override def run(): Unit = {
    val queueConsumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
        import data.subMsgManifest
        if (false) subMsgManifest // to avoid imports optimization
        import data.formats
        val msg = Serialization.read[Sub](new InputStreamReader(new ByteArrayInputStream(body), "UTF-8"))
        implicit val timeout = Timeout(1 minute)
        (consumer ? msg).onComplete {
          case Success(_) => channel.basicAck(envelope.getDeliveryTag, false)
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