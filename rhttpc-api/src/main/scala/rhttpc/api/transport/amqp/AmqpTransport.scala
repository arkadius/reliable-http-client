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
package rhttpc.api.transport.amqp

import java.io._

import akka.actor._
import akka.agent.Agent
import akka.pattern._
import akka.util.Timeout
import com.rabbitmq.client._
import org.json4s.Formats
import org.json4s.native._
import rhttpc.api.transport._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.{Try, Failure, Success}
import scala.util.control.Exception._

// TODO: actor-based, connection recovery
private[amqp] class AmqpTransport[PubMsg <: AnyRef, SubMsg](data: AmqpTransportCreateData[PubMsg, SubMsg], connection: Connection) extends PubSubTransport[PubMsg] {
  override def publisher(queueName: String): Publisher[PubMsg] = {
    val channel = connection.createChannel()
    channel.queueDeclare(queueName, true, false, false, null)
    val publisher = new AmqpPublisher(data, channel, queueName)
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber(queueName: String, consumer: ActorRef): Subscriber = {
    val channel = connection.createChannel()
    channel.basicQos(data.qos)
    channel.queueDeclare(queueName, true, false, false, null)
    new AmqpSubscriber(data, channel, queueName, consumer)
  }

  override def close(): Unit = {
    connection.close()
  }
}

object AmqpTransportFactory extends PubSubTransportFactory {
  override type DataT[P, S] = AmqpTransportCreateData[P, S]

  override def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): PubSubTransport[PubMsg] = {
    import collection.convert.wrapAsScala._
    val factory = new ConnectionFactory()
    factory.setAutomaticRecoveryEnabled(true)

    val connection = retry(n = 10, delay = 5000) {
      Try { // Could By IOException or TimeoutException
        val hosts = data.actorSystem.settings.config.getStringList("rabbitmq.hosts")
        val addresses = hosts.map(com.rabbitmq.client.Address.parseAddress).toArray
        factory.newConnection(addresses)
      }
    }

    new AmqpTransport[PubMsg, SubMsg](data, connection)
  }

  private def retry[T](n: Int, delay: Long)(fn: => Try[T]): T = {
    fn match {
      case Success(x) => x
      case _ if n > 1 =>
        Thread.sleep(delay)
        retry(n - 1, delay)(fn)
      case Failure(e) => throw e
    }
  }

}

case class AmqpTransportCreateData[PubMsg, SubMsg](actorSystem: ActorSystem, qos: Int = 10)
                                                  (implicit val subMsgManifest: Manifest[SubMsg],
                                                   val formats: Formats) extends TransportCreateData[PubMsg, SubMsg]

private[amqp] class AmqpPublisher[PubMsg <: AnyRef](data: AmqpTransportCreateData[PubMsg, _],
                                                    channel: Channel,
                                                    queueName: String) extends Publisher[PubMsg] with ConfirmListener {
  import data.actorSystem.dispatcher

  private val seqNoOnAckPromiseAgent = Agent[Map[Long, Promise[Unit]]](Map.empty)

  override def publish(msg: PubMsg): Future[Unit] = {
    val bos = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(bos, "UTF-8")
    try {
      import data.formats
      Serialization.write(msg, writer)
    } finally {
      writer.close()
    }
    val ackPromise = Promise[Unit]()
    for {
      _ <- seqNoOnAckPromiseAgent.alter { curr =>
        val publishSeqNo = channel.getNextPublishSeqNo
        data.actorSystem.log.debug(s"PUBLISH: $publishSeqNo")
        channel.basicPublish("", queueName, null, bos.toByteArray)
        curr + (publishSeqNo -> ackPromise)
      }
      ack <- ackPromise.future
    } yield ack
  }

  override def handleAck(deliveryTag: Long, multiple: Boolean): Unit = {
    data.actorSystem.log.debug(s"ACK: $deliveryTag, multiple = $multiple")
    confirm(deliveryTag, multiple)(_.success(Unit))
  }

  override def handleNack(deliveryTag: Long, multiple: Boolean): Unit = {
    data.actorSystem.log.debug(s"NACK: $deliveryTag, multiple = $multiple")
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

private[amqp] class AmqpSubscriber[Sub](data: AmqpTransportCreateData[_, Sub],
                                        channel: Channel,
                                        queueName: String,
                                        consumer: ActorRef) extends Subscriber {
  override def run(): Unit = {
    val queueConsumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
        import data.actorSystem.dispatcher
        import data.formats
        import data.subMsgManifest
        if (false) subMsgManifest // to avoid imports optimization
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

case object NoPubMsgAckException extends Exception(s"No acknowledgement for published message")