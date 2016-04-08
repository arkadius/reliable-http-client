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
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{AMQP, Channel, Connection}
import rhttpc.transport._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

trait AmqpTransport extends PubSubTransport with WithInstantPublisher with WithDelayedPublisher {
  def queuesStats: Future[Map[String, AmqpQueueStats]]
}

// TODO: actor-based, connection recovery
private[rhttpc] class AmqpTransportImpl(connection: Connection,
                                        exchangeName: String,
                                        serializer: Serializer,
                                        deserializer: Deserializer,
                                        consumeTimeout: FiniteDuration,
                                        nackDelay: FiniteDuration,
                                        declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk,
                                        declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk,
                                        prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties])
                                       (implicit actorSystem: ActorSystem) extends AmqpTransport {

  import actorSystem.dispatcher

  private lazy val statsChannel = connection.createChannel()
  
  private val queueNamesAgent = Agent[Set[String]](Set.empty)
  
  override def publisher[PubMsg <: AnyRef](queueData: OutboundQueueData): AmqpPublisher[PubMsg] = {
    val channel = connection.createChannel()
    declarePublisherQueue(AmqpDeclareOutboundQueueData(queueData, exchangeName, channel))
    queueNamesAgent.send(_ + queueData.name)
    val publisher = new AmqpPublisher[PubMsg](
      channel = channel,
      queueName = queueData.name,
      exchangeName = exchangeName,
      serializer = serializer,
      prepareProperties = prepareProperties
    )
    channel.addConfirmListener(publisher)
    channel.confirmSelect()
    publisher
  }

  override def subscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val subscribers = (1 to queueData.parallelConsumers).map { _ =>
      val channel = connection.createChannel()
      declareSubscriberQueue(AmqpDeclareInboundQueueData(queueData, channel))
      queueNamesAgent.send(_ + queueData.name)
      new AmqpSubscriber[SubMsg](
        channel = channel,
        queueName = queueData.name,
        consumer = consumer,
        deserializer = deserializer,
        consumeTimeout = consumeTimeout,
        nackDelay = nackDelay
      ) with SendingSimpleMessage[SubMsg]
    }
    new SubscriberAggregate[SubMsg](subscribers)
  }

  override def fullMessageSubscriber[SubMsg: Manifest](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] = {
    val subscribers = (1 to queueData.parallelConsumers).map { _ =>
      val channel = connection.createChannel()
      declareSubscriberQueue(AmqpDeclareInboundQueueData(queueData, channel))
      new AmqpSubscriber[SubMsg](
        channel = channel,
        queueName = queueData.name,
        consumer = consumer,
        deserializer = deserializer,
        consumeTimeout = consumeTimeout,
        nackDelay = nackDelay
      ) with SendingFullMessage[SubMsg]
    }
    new SubscriberAggregate[SubMsg](subscribers)
  }
  
  override def queuesStats: Future[Map[String, AmqpQueueStats]] = {
    queueNamesAgent.future().map { names =>
      names.map { queueName =>
        val dlqQueueName = AmqpDefaults.prepareDlqName(queueName)
        val stats = AmqpQueueStats(
          messageCount = messageCount(queueName),
          consumerCount = consumerCount(queueName),
          dlqMessageCount = messageCount(dlqQueueName),
          dlqConsumerCount = consumerCount(dlqQueueName)
        )
        queueName -> stats
      }.toMap
    }
  }

  private def messageCount(queueName: String): Long =
    Try(statsChannel.messageCount(queueName)).getOrElse(0L)

  private def consumerCount(queueName: String): Long =
    Try(statsChannel.consumerCount(queueName)).getOrElse(0L)

  override def stop(): Future[Unit] = Future.successful(Unit)

}

case class AmqpQueueStats(messageCount: Long, consumerCount: Long, dlqMessageCount: Long, dlqConsumerCount: Long)

object AmqpQueueStats {
  def zero = AmqpQueueStats(0, 0, 0, 0)
}

object AmqpTransport {

  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      exchangeName: String = AmqpDefaults.instantExchangeName,
                                      consumeTimeout: FiniteDuration = AmqpDefaults.consumeTimeout,
                                      nackDelay: FiniteDuration = AmqpDefaults.nackDelay,
                                      declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk = AmqpDefaults.declarePublisherQueueWithDelayedExchangeIfNeed,
                                      declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk = AmqpDefaults.declareSubscriberQueue,
                                      prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = AmqpDefaults.preparePersistentMessageProperties)
                                     (implicit actorSystem: ActorSystem,
                                      serializer: Serializer,
                                      deserializer: Deserializer): AmqpTransport = {
    new AmqpTransportImpl(
      connection = connection,
      exchangeName = exchangeName,
      serializer = serializer,
      deserializer = deserializer,
      consumeTimeout = consumeTimeout,
      nackDelay = nackDelay,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      prepareProperties = AmqpDefaults.preparePersistentMessageProperties
    )
  }

}

case class AmqpDeclareInboundQueueData(queueData: InboundQueueData, channel: Channel)

case class AmqpDeclareOutboundQueueData(queueData: OutboundQueueData, exchangeName: String, channel: Channel)