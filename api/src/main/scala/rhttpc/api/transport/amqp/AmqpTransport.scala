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

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import com.spingo._
import com.spingo.op_rabbit._
import org.json4s.Formats
import rhttpc.api.transport.{Subscription, _}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

private[amqp] class AmqpTransport[PubMsg, SubMsg](val rabbitControl: ActorRef)
                                                 (implicit val marshaller: RabbitMarshaller[PubMsg],
                                                  val unmarshaller: RabbitUnmarshaller[SubMsg],
                                                  val executionContext: ExecutionContext) extends PubSubTransport[PubMsg, SubMsg] {
  override def publisher(queueName: String): Publisher[PubMsg] = new AmqpPublisher(this, queueName)

  override def subscription(queueName: String, consumer: ActorRef): Subscription = new AmqpSubscriber(this, queueName, consumer)
}

object AmqpTransportFactory extends PubSubTransportFactory {
  override type DataT[P, S] = AmqpTransportCreateData[P, S]

  override def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): PubSubTransport[PubMsg, SubMsg] = {
    val rabbitControl = data.actorSystem.actorOf(Props[RabbitControl])
    import data.actorSystem.dispatcher
    import data.formats
    import data.subMsgManifest
    import com.spingo.op_rabbit.Json4sSupport._
    new AmqpTransport[PubMsg, SubMsg](rabbitControl)
  }
}

case class AmqpTransportCreateData[PubMsg, SubMsg](actorSystem: ActorSystem)
                                                  (implicit val formats: Formats,
                                                   val subMsgManifest: Manifest[SubMsg]) extends TransportCreateData[PubMsg, SubMsg]

private[amqp] class AmqpPublisher[PubMsg](transport: AmqpTransport[PubMsg, _], queueName: String) extends Publisher[PubMsg] {
  override def publish(msg: PubMsg): Future[Unit] = {
    import transport.{executionContext, marshaller}
    implicit val timeout = Timeout(10 seconds)
    (transport.rabbitControl ? ConfirmedMessage(QueuePublisherDeclaringQueueIfNotExist(queueName), msg)).mapTo[Boolean].map { ack =>
      if (!ack) throw new NoAckException(msg)
    }
  }
}

private[amqp] class AmqpSubscriber[Sub](transport: AmqpTransport[_, Sub], queueName: String, consumer: ActorRef) extends Subscription {
  override def run(): Unit = {
    val subscription = new op_rabbit.consumer.Subscription {
      import transport.{executionContext, unmarshaller}
      // A qos of 3 will cause up to 3 concurrent messages to be processed at any given time.
      def config = channel(qos = 3) {
        consume(queue(queueName)) {
          body(as[Sub]) { msg =>
            implicit val timeout = Timeout(10 seconds)
            ack(consumer ? msg)
          }
        }
      }
    }
    transport.rabbitControl ! subscription
  }
}

class NoAckException(msg: Any) extends Exception(s"No acknowledgement for msg: $msg")