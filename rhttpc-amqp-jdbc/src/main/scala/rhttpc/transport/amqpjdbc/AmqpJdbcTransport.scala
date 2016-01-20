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
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{AMQP, Connection}
import rhttpc.transport.amqpjdbc.slick.SlickJdbcScheduledMessagesRepository
import rhttpc.transport._
import rhttpc.transport.amqp.{AmqpDeclareInboundQueueData, AmqpDeclareOutboundQueueData, AmqpTransport}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait AmqpJdbcTransport[PubMsg <: AnyRef, SubMsg] extends PubSubTransport[PubMsg, SubMsg] with WithInstantPublisher with WithDelayedPublisher {
  def queuesStats: Future[Map[String, Int]]
}

private[amqpjdbc] class AmqpJdbcTransportImpl[PubMsg <: AnyRef, SubMsg](underlying: AmqpTransport[PubMsg, SubMsg],
                                                                        schedulerByQueueAndPublisher: (String, Publisher[PubMsg]) => AmqpJdbcScheduler[PubMsg],
                                                                        repo: ScheduledMessagesRepository)
  extends AmqpJdbcTransport[PubMsg, SubMsg] {

  override def publisher(queueData: OutboundQueueData): Publisher[PubMsg] = {
    val underlyingPublisher = underlying.publisher(queueData)
    val scheduler = schedulerByQueueAndPublisher(queueData.name, underlyingPublisher)
    new AmqpJdbcPublisher[PubMsg](underlyingPublisher, queueData.name, scheduler)
  }

  override def subscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    underlying.subscriber(queueData, consumer)

  override def fullMessageSubscriber(queueData: InboundQueueData, consumer: ActorRef): Subscriber[SubMsg] =
    underlying.fullMessageSubscriber(queueData, consumer)

  override def queuesStats: Future[Map[String, Int]] = {
    repo.queuesStats
  }

}

object AmqpJdbcTransport {

  private val schedulersCache = TrieMap[String, AmqpJdbcScheduler[_]]()

  def apply[PubMsg <: AnyRef, SubMsg](connection: Connection,
                                      driver: JdbcDriver,
                                      db: JdbcBackend.Database,
                                      checkInterval: FiniteDuration = 30 seconds,
                                      schedulerMessagesFetchBatchSize: Int = 50,
                                      exchangeName: String = "",
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
      connection,
      exchangeName,
      declarePublisherQueue,
      declareSubscriberQueue,
      prepareProperties
    )
    val repo = new SlickJdbcScheduledMessagesRepository(driver, db)
    def schedulerByQueueAndPublisher(queueName: String, publisher: Publisher[PubMsg]): AmqpJdbcScheduler[PubMsg] = {
      def createScheduler =
        new AmqpJdbcSchedulerImpl[PubMsg](
          actorSystem.scheduler,
          checkInterval,
          repo,
          queueName,
          schedulerMessagesFetchBatchSize,
          publisher,
          msgSerializer,
          msgDeserializer
        )
      schedulersCache.getOrElseUpdate(queueName, createScheduler).asInstanceOf[AmqpJdbcScheduler[PubMsg]]
    }
    new AmqpJdbcTransportImpl(underlying, schedulerByQueueAndPublisher, repo)
  }

}