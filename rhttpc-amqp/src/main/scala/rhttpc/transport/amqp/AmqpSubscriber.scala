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
import akka.agent.Agent
import akka.pattern._
import akka.util.Timeout
import com.rabbitmq.client._
import org.slf4j.LoggerFactory
import rhttpc.transport._
import rhttpc.utils.Recovered._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

private[amqp] abstract class AmqpSubscriber[Sub](channel: Channel,
                                                 queueName: String,
                                                 consumer: ActorRef,
                                                 deserializer: Deserializer[Sub],
                                                 consumeTimeout: FiniteDuration,
                                                 nackDelay: FiniteDuration)
                                                (implicit system: ActorSystem)
  extends Subscriber[Sub] {

  import system.dispatcher

  private lazy val logger = LoggerFactory.getLogger(getClass)

  private val pendingConsumePromises = Agent[Set[Promise[Unit]]](Set.empty)

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
            logger.error(s"REJECT: $deliveryTag because of parse failure of: $stringMsg", ex)
            channel.basicReject(deliveryTag, false)
        }
      }
    }
    consumerTag = Some(channel.basicConsume(queueName, false, queueConsumer))
  }

  protected def prepareMessage(deserializedMessage: Sub, properties: AMQP.BasicProperties): Any

  private def handleDeserializedMessage(msgObj: Any, deliveryTag: Long) = {
    implicit val timeout = Timeout(consumeTimeout)
    val consumePromise = Promise[Unit]()
    def complete() = {
      pendingConsumePromises.send { current =>
        consumePromise.success(Unit)
        current - consumePromise
      }
    }
    val replyFuture = for {
      _ <- pendingConsumePromises.alter(_ + consumePromise)
      _ <- consumer ? msgObj
    } yield Unit
    replyFuture onComplete handleConsumerResponse(deliveryTag, complete)
  }

  private def handleConsumerResponse[U](deliveryTag: Long, complete: () => Unit): Try[Any] => Unit = {
    case Success(_) =>
      logger.debug(s"ACK: $deliveryTag")
      channel.basicAck(deliveryTag, false)
      complete()
    case Failure(ex: AskTimeoutException) =>
      logger.debug(s"REJECT: $deliveryTag because of ask timeout", ex)
      channel.basicReject(deliveryTag, false)
      complete()
    case Failure(ex: Exception with RejectingMessage) =>
      logger.debug(s"REJECT: $deliveryTag because of rejecting failure", ex)
      channel.basicReject(deliveryTag, false)
      complete()
    case Failure(ex) =>
      logger.debug(s"Will NACK: $deliveryTag after $nackDelay because of failure", ex)
      system.scheduler.scheduleOnce(nackDelay) {
        logger.debug(s"NACK: $deliveryTag because of previous failure")
        channel.basicNack(deliveryTag, false, true)
        complete()
      }
  }

  override def stop(): Future[Unit] = {
    recovered("canceling consumer", consumerTag.foreach(channel.basicCancel))
    recoveredFuture("completing consuming", currentConsumingFuturesComplete)
      .map(_ => recovered("closing channel", channel.close()))
  }

  private def currentConsumingFuturesComplete: Future[Unit] =
    pendingConsumePromises.future()
      .flatMap(set => Future.sequence(set.map(_.future)))
      .map(_ => Unit)
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