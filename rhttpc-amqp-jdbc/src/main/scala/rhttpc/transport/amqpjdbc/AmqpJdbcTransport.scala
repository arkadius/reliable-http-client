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
package rhttpc.transport.amqpjdbc

import java.util.concurrent.ConcurrentLinkedQueue

import _root_.slick.driver.JdbcDriver
import _root_.slick.jdbc.JdbcBackend
import akka.actor.{ActorRef, ActorSystem}
import akka.agent.Agent
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{AMQP, Connection}
import rhttpc.transport._
import rhttpc.transport.amqp.{AmqpDeclareInboundQueueData, AmqpDeclareOutboundQueueData, AmqpQueueStats, AmqpTransport}
import rhttpc.transport.amqpjdbc.slick.SlickJdbcScheduledMessagesRepository

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait AmqpJdbcTransport[-PubMsg <: AnyRef, +SubMsg] extends PubSubTransport[PubMsg, SubMsg] with WithInstantPublisher with WithDelayedPublisher {
  def queuesStats: Future[Map[String, AmqpJdbcQueueStats]]
}

private[amqpjdbc] class AmqpJdbcTransportImpl[PubMsg <: AnyRef, SubMsg](underlying: AmqpTransport[PubMsg, SubMsg],
                                                                        schedulerByQueueAndPublisher: (String, Publisher[PubMsg]) => AmqpJdbcScheduler[PubMsg],
                                                                        repo: ScheduledMessagesRepository)
                                                                       (implicit ec: ExecutionContext)
  extends AmqpJdbcTransport[PubMsg, SubMsg] {

  private val publisherQueueNamesAgent = Agent[Set[String]](Set.empty)

  override def publisher(queueData: OutboundQueueData): Publisher[PubMsg] = {
    val underlyingPublisher = underlying.publisher(queueData)
    val scheduler = schedulerByQueueAndPublisher(queueData.name, underlyingPublisher)
    publisherQueueNamesAgent.send(_ + queueData.name)
    new AmqpJdbcPublisher[PubMsg](underlyingPublisher, queueData.name, scheduler)
  }

  override def subscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    underlying.subscriber(queueData, consumer)

  override def fullMessageSubscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    underlying.fullMessageSubscriber(queueData, consumer)

  override def queuesStats: Future[Map[String, AmqpJdbcQueueStats]] = {
    for {
      amqpStats <- underlying.queuesStats
      currentPublisherQueueNames <- publisherQueueNamesAgent.future()
      scheduledStats <- repo.queuesStats(currentPublisherQueueNames)
    } yield {
      val mergedKeys = amqpStats.keySet ++ scheduledStats.keySet
      mergedKeys.map { queueName =>
        val amqpQueueStats = amqpStats.getOrElse(queueName, AmqpQueueStats.zero)
        val stats = AmqpJdbcQueueStats(
          amqpStats = amqpQueueStats,
          scheduledMessageCount = scheduledStats.getOrElse(queueName, 0)
        )
        queueName -> stats
      }.toMap
    }
  }

}

case class AmqpJdbcQueueStats(amqpStats: AmqpQueueStats, scheduledMessageCount: Int)

object AmqpJdbcTransport {

  private val schedulersCache = TrieMap[String, AmqpJdbcScheduler[_]]()

  private val transportsCache = new ConcurrentLinkedQueue[AmqpJdbcTransport[Nothing, Any]]()

  def aggregatedQueuesStats(implicit ec: ExecutionContext): Future[Map[String, AmqpJdbcQueueStats]] = {
    import collection.convert.wrapAsScala._
    Future.sequence(transportsCache.map { transport =>
      transport.queuesStats
    }).map {
      _.foldLeft(Map.empty[String, AmqpJdbcQueueStats])(_ ++ _)
    }
  }

  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      driver: JdbcDriver,
                                      db: JdbcBackend.Database,
                                      schedulerCheckInterval: FiniteDuration = AmqpJdbcDefaults.schedulerCheckInterval,
                                      schedulerMessagesFetchBatchSize: Int = AmqpJdbcDefaults.schedulerMessagesFetchBatchSize,
                                      exchangeName: String = AmqpJdbcDefaults.instantExchangeName,
                                      consumeTimeout: FiniteDuration = AmqpJdbcDefaults.consumeTimeout,
                                      nackDelay: FiniteDuration = AmqpJdbcDefaults.nackDelay,
                                      declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk = AmqpJdbcDefaults.declarePublisherQueueWithExchangeIfNeed,
                                      declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk = AmqpJdbcDefaults.declareSubscriberQueue,
                                      prepareProperties: PartialFunction[Message[Any], AMQP.BasicProperties] = AmqpJdbcDefaults.preparePersistentMessageProperties)
                                     (implicit actorSystem: ActorSystem,
                                      serializer: Serializer[PubMsg],
                                      deserializer: Deserializer[SubMsg],
                                      msgSerializer: Serializer[Message[PubMsg]],
                                      msgDeserializer: Deserializer[Message[PubMsg]]): AmqpJdbcTransport[PubMsg, SubMsg] = {
    import actorSystem.dispatcher
    val underlying = AmqpTransport[PubMsg, SubMsg](
      connection = connection,
      exchangeName = exchangeName,
      consumeTimeout = consumeTimeout,
      nackDelay = nackDelay,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      prepareProperties = prepareProperties      
    )
    val repo = new SlickJdbcScheduledMessagesRepository(driver, db)
    def schedulerByQueueAndPublisher(queueName: String, publisher: Publisher[PubMsg]): AmqpJdbcScheduler[PubMsg] = {
      def createScheduler =
        new AmqpJdbcSchedulerImpl[PubMsg](
          scheduler = actorSystem.scheduler,
          checkInterval = schedulerCheckInterval,
          repo = repo,
          queueName = queueName,
          batchSize = schedulerMessagesFetchBatchSize,
          publisher = publisher,
          serializer = msgSerializer,
          deserializer = msgDeserializer
        )
      schedulersCache.getOrElseUpdate(queueName, createScheduler).asInstanceOf[AmqpJdbcScheduler[PubMsg]]
    }
    val transport = new AmqpJdbcTransportImpl(underlying, schedulerByQueueAndPublisher, repo)
    transportsCache.add(transport)
    transport
  }

}