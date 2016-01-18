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
import org.slf4j.LoggerFactory
import rhttpc.transport._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Try, Failure, Success}

private[amqp] abstract class AmqpSubscriber[Sub](channel: Channel,
                                                 queueName: String,
                                                 consumer: ActorRef,
                                                 deserializer: Deserializer[Sub])
                                                (implicit ec: ExecutionContext)
  extends Subscriber[Sub] {

  private lazy val logger = LoggerFactory.getLogger(getClass)

  override def start(): Unit = {
    val queueConsumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
        val deliveryTag = envelope.getDeliveryTag
        val stringMsg = new String(body, "UTF-8")
        val tryDeserializedMessage = deserializer.deserialize(stringMsg)
        tryDeserializedMessage match {
          case Success(deserializedMessage) =>
            val msgToSend = prepareMessage(deserializedMessage, properties: AMQP.BasicProperties)
            handleDeserializedMessage(msgToSend, deliveryTag)
          case Failure(ex) =>
            channel.basicReject(deliveryTag, false)
            logger.error(s"Message: [$stringMsg] rejected because of parse failure", ex)
        }
      }
    }
    channel.basicConsume(queueName, false, queueConsumer)
  }

  protected def prepareMessage(deserializedMessage: Sub, properties: AMQP.BasicProperties): Any

  private def handleDeserializedMessage(msgObj: Any, deliveryTag: Long) = {
    implicit val timeout = Timeout(5 minute)
    (consumer ? msgObj) onComplete handleConsumerResponse(deliveryTag)
  }

  private def handleConsumerResponse[U](deliveryTag: Long): Try[Any] => Unit = {
    case Success(_) =>
      channel.basicAck(deliveryTag, false)
    case Failure(_ : Exception with RejectingMessage) =>
      channel.basicReject(deliveryTag, false)
    case Failure(_) =>
      channel.basicNack(deliveryTag, false, true)
  }

  override def stop(): Unit = {
    channel.close()
  }

}

trait SendingSimpleMessage[Sub] { self: AmqpSubscriber[Sub] =>

  override protected def prepareMessage(deserializedMessage: Sub, properties: AMQP.BasicProperties): Any = {
    deserializedMessage
  }

}

trait SendingFullMessage[Sub] { self: AmqpSubscriber[Sub] =>

  override protected def prepareMessage(deserializedMessage: Sub, properties: AMQP.BasicProperties): Any = {
    import collection.convert.wrapAsScala._
    InstantMessage(deserializedMessage, properties.getHeaders.asInstanceOf[java.util.Map[String, Any]].toMap)
  }

}