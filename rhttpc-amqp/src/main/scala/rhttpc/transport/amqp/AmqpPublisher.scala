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
import com.rabbitmq.client._
import org.slf4j.LoggerFactory
import rhttpc.transport.{Publisher, Serializer}

import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

private[amqp] class AmqpPublisher[PubMsg <: AnyRef](data: AmqpTransportCreateData[PubMsg, _],
                                                             channel: Channel,
                                                             queueName: String)
                                                            (implicit val serializer: Serializer[PubMsg])
  extends Publisher[PubMsg] with ConfirmListener {

  private val logger = LoggerFactory.getLogger(getClass)

  import data.executionContext

  private val seqNoOnAckPromiseAgent = Agent[Map[Long, Promise[Unit]]](Map.empty)

  override def publish(msg: PubMsg): Future[Unit] = {
    val bos = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(bos, "UTF-8")
    try {
      writer.write(serializer.serialize(msg))
    } finally {
      writer.close()
    }
    val ackPromise = Promise[Unit]()
    for {
      _ <- seqNoOnAckPromiseAgent.alter { curr =>
        val publishSeqNo = channel.getNextPublishSeqNo
        logger.debug(s"PUBLISH: $publishSeqNo")
        channel.basicPublish(data.exchangeName, queueName, null, bos.toByteArray)
        curr + (publishSeqNo -> ackPromise)
      }
      ack <- ackPromise.future
    } yield ack
  }

  override def handleAck(deliveryTag: Long, multiple: Boolean): Unit = {
    logger.debug(s"ACK: $deliveryTag, multiple = $multiple")
    confirm(deliveryTag, multiple)(_.success(Unit))
  }

  override def handleNack(deliveryTag: Long, multiple: Boolean): Unit = {
    logger.debug(s"NACK: $deliveryTag, multiple = $multiple")
    confirm(deliveryTag, multiple)(_.failure(NoPubMsgAckException))
  }

  private def confirm(deliveryTag: Long, multiple: Boolean)(complete: Promise[Unit] => Unit): Unit = {
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

  override def close(): Unit = {
    channel.close()
  }
}

case object NoPubMsgAckException extends Exception(s"No acknowledgement for published message")