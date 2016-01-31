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

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import rhttpc.utils.Recovered._

private[amqp] abstract class AmqpSubscriber[Sub: Manifest](channel: Channel,
                                                           queueName: String,
                                                           consumer: ActorRef,
                                                           deserializer: Deserializer,
                                                           consumeTimeout: FiniteDuration,
                                                           nackDelay: FiniteDuration)
                                                          (implicit system: ActorSystem)
  extends Subscriber[Sub] {

  import system.dispatcher

  private lazy val logger = LoggerFactory.getLogger(getClass)

  @volatile private var consumerTag: Option[String] = None

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
    consumerTag = Some(channel.basicConsume(queueName, false, queueConsumer))
  }

  protected def prepareMessage(deserializedMessage: Sub, properties: AMQP.BasicProperties): Any

  private def handleDeserializedMessage(msgObj: Any, deliveryTag: Long) = {
    implicit val timeout = Timeout(consumeTimeout)
    (consumer ? msgObj) onComplete handleConsumerResponse(deliveryTag)
  }

  private def handleConsumerResponse[U](deliveryTag: Long): Try[Any] => Unit = {
    case Success(_) =>
      logger.debug(s"ACK: $deliveryTag")
      channel.basicAck(deliveryTag, false)
    case Failure(ex : Exception with RejectingMessage) =>
      logger.debug(s"REJECT: $deliveryTag because of rejecting failure", ex)
      channel.basicReject(deliveryTag, false)
    case Failure(ex) =>
      logger.debug(s"Will NACK: $deliveryTag after $nackDelay because of failure", ex)
      system.scheduler.scheduleOnce(nackDelay) {
        logger.debug(s"NACK: $deliveryTag because of previous failure")
        channel.basicNack(deliveryTag, false, true)
      }
  }

  override def stop(): Future[Unit] = {
    Future {
      recovered("canceling consumer", consumerTag.foreach(channel.basicCancel))
      recovered("closing channel", channel.close())
    }
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
    Message(deserializedMessage, properties.getHeaders.asInstanceOf[java.util.Map[String, Any]].toMap)
  }

}