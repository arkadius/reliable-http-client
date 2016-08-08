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

import _root_.slick.driver.JdbcDriver
import _root_.slick.jdbc.JdbcBackend
import akka.actor.{ActorRef, ActorSystem}
import akka.agent.Agent
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{AMQP, Connection}
import rhttpc.transport.SerializingPublisher.SerializedMessage
import rhttpc.transport._
import rhttpc.transport.amqp.{AmqpDeclareInboundQueueData, AmqpDeclareOutboundQueueData, AmqpQueueStats, AmqpTransport}
import rhttpc.transport.amqpjdbc.slick.SlickJdbcScheduledMessagesRepository

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait AmqpJdbcTransport extends PubSubTransport {
  def queuesStats: Future[Map[String, AmqpJdbcQueueStats]]
}

private[amqpjdbc] class AmqpJdbcTransportImpl(underlying: AmqpTransport,
                                              repo: ScheduledMessagesRepository,
                                              schedulerCheckInterval: FiniteDuration,
                                              schedulerMessagesFetchBatchSize: Int)
                                             (implicit actorSystem: ActorSystem)
  extends AmqpJdbcTransport {

  import actorSystem.dispatcher

  private val schedulersCache = TrieMap[String, AmqpJdbcScheduler[_]]()

  private val publisherQueueNamesAgent = Agent[Set[String]](Set.empty)

  override def publisher[PubMsg: Serializer](queueData: OutboundQueueData): Publisher[PubMsg] = {
    val underlyingPublisher = underlying.publisher[PubMsg](queueData).asInstanceOf[SerializingPublisher[PubMsg]]
    publisherQueueNamesAgent.send(_ + queueData.name)
    if (queueData.delayed) {
      val scheduler = schedulerByQueueAndPublisher(queueData.name, underlyingPublisher)
      def removeFromCache(): Future[Unit] = {
        schedulersCache.remove(queueData.name)
        Future.successful(Unit)
      }
      new AmqpJdbcPublisher[PubMsg](underlyingPublisher, queueData.name, scheduler, removeFromCache())
    } else {
      underlyingPublisher
    }
  }

  private def schedulerByQueueAndPublisher[PubMsg: Serializer](queueName: String,
                                                               publisher: SerializingPublisher[PubMsg]): AmqpJdbcScheduler[PubMsg] = {
    def createScheduler =
      new AmqpJdbcSchedulerImpl[PubMsg](
        scheduler = actorSystem.scheduler,
        checkInterval = schedulerCheckInterval,
        repo = repo,
        queueName = queueName,
        batchSize = schedulerMessagesFetchBatchSize,
        publisher = publisher
      )
    schedulersCache.getOrElseUpdate(queueName, createScheduler).asInstanceOf[AmqpJdbcScheduler[PubMsg]]
  }

  override def subscriber[SubMsg: Deserializer](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    underlying.subscriber(queueData, consumer)

  override def fullMessageSubscriber[SubMsg: Deserializer](queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
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

  override def stop(): Future[Unit] = underlying.stop()
}

case class AmqpJdbcQueueStats(amqpStats: AmqpQueueStats, scheduledMessageCount: Int)

object AmqpJdbcTransport {

  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      driver: JdbcDriver,
                                      db: JdbcBackend.Database,
                                      schedulerCheckInterval: FiniteDuration = AmqpJdbcDefaults.schedulerCheckInterval,
                                      schedulerMessagesFetchBatchSize: Int = AmqpJdbcDefaults.schedulerMessagesFetchBatchSize,
                                      prepareExchangeName: OutboundQueueData => String = _ => AmqpJdbcDefaults.instantExchangeName,
                                      consumeTimeout: FiniteDuration = AmqpJdbcDefaults.consumeTimeout,
                                      nackDelay: FiniteDuration = AmqpJdbcDefaults.nackDelay,
                                      declarePublisherQueue: AmqpDeclareOutboundQueueData => DeclareOk = AmqpJdbcDefaults.declarePublisherQueueWithExchangeIfNeed,
                                      declareSubscriberQueue: AmqpDeclareInboundQueueData => DeclareOk = AmqpJdbcDefaults.declareSubscriberQueue,
                                      prepareProperties: PartialFunction[SerializedMessage, AMQP.BasicProperties] = AmqpJdbcDefaults.preparePersistentMessageProperties)
                                     (implicit actorSystem: ActorSystem): AmqpJdbcTransport = {
    val underlying = AmqpTransport[PubMsg, SubMsg](
      connection = connection,
      prepareExchangeName = prepareExchangeName,
      consumeTimeout = consumeTimeout,
      nackDelay = nackDelay,
      declarePublisherQueue = declarePublisherQueue,
      declareSubscriberQueue = declareSubscriberQueue,
      prepareProperties = prepareProperties      
    )
    val repo = new SlickJdbcScheduledMessagesRepository(driver, db)(actorSystem.dispatcher)
    new AmqpJdbcTransportImpl(
      underlying,
      repo,
      schedulerCheckInterval,
      schedulerMessagesFetchBatchSize
    )
  }

}