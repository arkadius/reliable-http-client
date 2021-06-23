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
import akka.agent.Agent
import com.github.ghik.silencer.silent
import com.rabbitmq.client._
import org.slf4j.LoggerFactory
import rhttpc.transport.SerializingPublisher.SerializedMessage
import rhttpc.transport.{Message, Publisher, Serializer, SerializingPublisher}
import rhttpc.utils.Recovered._

import scala.concurrent.{ExecutionContext, Future, Promise}

private[amqp] class AmqpPublisher[PubMsg](channel: Channel,
                                          queueName: String,
                                          exchangeName: String,
                                          protected val serializer: Serializer[PubMsg],
                                          prepareProperties: PartialFunction[SerializedMessage, AMQP.BasicProperties])
                                         (implicit ec: ExecutionContext)
  extends SerializingPublisher[PubMsg] with ConfirmListener {

  private lazy val logger = LoggerFactory.getLogger(getClass)

  @silent private val seqNoOnAckPromiseAgent = Agent[Map[Long, Promise[Unit]]](Map.empty)

  override private[rhttpc] def publishSerialized(msg: SerializedMessage): Future[Unit] = {
    val properties = prepareProperties.applyOrElse(
      msg,
      (_: SerializedMessage) => throw new IllegalArgumentException(s"Not supported message type: $msg")
    )
    val ackPromise = Promise[Unit]()
    for {
      _ <- seqNoOnAckPromiseAgent.alter { curr =>
        val publishSeqNo = channel.getNextPublishSeqNo
        logger.debug(s"PUBLISH: $publishSeqNo")
        channel.basicPublish(exchangeName, queueName, properties, msg.content)
        curr + (publishSeqNo -> ackPromise)
      }
      ack <- ackPromise.future
    } yield ack
  }

  override def handleAck(deliveryTag: Long, multiple: Boolean): Unit = {
    logger.debug(s"ACK: $deliveryTag, multiple = $multiple")
    confirm(deliveryTag, multiple)(_.success(()))
  }

  override def handleNack(deliveryTag: Long, multiple: Boolean): Unit = {
    logger.debug(s"NACK: $deliveryTag, multiple = $multiple")
    confirm(deliveryTag, multiple)(_.failure(NoPubMsgAckException))
  }

  private def confirm(deliveryTag: Long, multiple: Boolean)
                     (complete: Promise[Unit] => Unit): Unit = {
    seqNoOnAckPromiseAgent.alter { curr =>
      val (toAck, rest) = curr.partition {
        case (seqNo, ackPromise) =>
          seqNo == deliveryTag || multiple && seqNo <= deliveryTag
      }
      toAck.foreach {
        case (seqNo, ackPromise) => complete(ackPromise)
      }
      rest
    }
  }

  override def start(): Unit = {}

  override def stop(): Future[Unit] = {
    recoveredFuture("completing publishing", currentPublishingFuturesComplete)
      .map(_ => recovered("channel closing", channel.close()))
  }

  private def currentPublishingFuturesComplete: Future[Unit] =
    seqNoOnAckPromiseAgent.future()
      .flatMap(map => Future.sequence(map.values.map(_.future)))
      .map(_ => ())
}

case object NoPubMsgAckException extends Exception(s"No acknowledgement for published message")