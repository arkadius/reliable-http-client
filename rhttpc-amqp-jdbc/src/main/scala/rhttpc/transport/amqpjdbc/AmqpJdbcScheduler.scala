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

import akka.actor.{Cancellable, Scheduler}
import org.slf4j.LoggerFactory
import rhttpc.transport.SerializingPublisher.SerializedMessage
import rhttpc.transport._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

private[amqpjdbc] trait AmqpJdbcScheduler[PubMsg] {

  def schedule(msg: Message[PubMsg], delay: FiniteDuration): Future[Unit]

  def start(): Unit

  def stop(): Future[Unit]

}

private[amqpjdbc] class AmqpJdbcSchedulerImpl[PubMsg](scheduler: Scheduler,
                                                      checkInterval: FiniteDuration,
                                                      repo: ScheduledMessagesRepository,
                                                      queueName: String,
                                                      batchSize: Int,
                                                      publisher: SerializingPublisher[PubMsg])
                                                     (implicit ec: ExecutionContext,
                                                      serializer: Serializer[PubMsg]) extends AmqpJdbcScheduler[PubMsg] {
  private val logger = LoggerFactory.getLogger(getClass)

  private var ran: Boolean = false
  private var scheduledCheck: Option[Cancellable] = None
  private var currentPublishedFetchedFuture: Future[Int] = Future.successful(0)

  override def schedule(msg: Message[PubMsg], delay: FiniteDuration): Future[Unit] = {
    val serialized = serializer.serialize(msg.content)
    repo.save(MessageToSchedule(queueName, serialized, msg.properties, delay))
  }

  override def start(): Unit = {
    synchronized {
      if (!ran) {
        ran = true
        publishFetchedMessagesThanReschedule()
      }
    }
  }

  private def publishFetchedMessagesThanReschedule(): Unit = {
    synchronized {
      if (ran) {
        val publishedFetchedFuture = repo.fetchMessagesShouldByRun(queueName, batchSize)(publish)
        currentPublishedFetchedFuture = publishedFetchedFuture
        publishedFetchedFuture onComplete handlePublicationResult
      }
    }
  }

  private def publish(messages: Seq[ScheduledMessage]): Future[Seq[Unit]] = {
    if (messages.nonEmpty) {
      logger.debug(s"Fetched ${messages.size}, publishing")
    }
    val handlingFutures = messages.map { message =>
      publisher.publishSerialized(SerializedMessage(message.content.getBytes(), message.properties))
    }
    Future.sequence(handlingFutures)
  }

  private def handlePublicationResult(tryResult: Try[Int]): Unit = {
    tryResult match {
      case Failure(ex) =>
        logger.error("Exception while publishing fetched messages", ex)
      case _ =>
    }
    synchronized {
      if (ran) {
        scheduledCheck = Some(scheduler.scheduleOnce(checkInterval)(publishFetchedMessagesThanReschedule()))
      } else {
        logger.debug(s"Scheduler is stopping, next check will be skipped")
      }
    }
  }

  override def stop(): Future[Unit] = {
    synchronized {
      scheduledCheck.foreach(_.cancel())
      ran = false
      currentPublishedFetchedFuture.map(_ => Unit)
    }
  }

}