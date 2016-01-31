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
import rhttpc.transport.{Deserializer, Message, Publisher, Serializer}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private[amqpjdbc] trait AmqpJdbcScheduler[PubMsg] {

  def schedule(msg: Message[PubMsg], delay: FiniteDuration): Future[Unit]

  def start(): Unit

  def stop(): Future[Unit]

}

private[amqpjdbc] class AmqpJdbcSchedulerImpl[PubMsg <: AnyRef](scheduler: Scheduler,
                                                                checkInterval: FiniteDuration,
                                                                repo: ScheduledMessagesRepository,
                                                                queueName: String,
                                                                batchSize: Int,
                                                                publisher: Publisher[PubMsg])
                                                               (implicit ec: ExecutionContext,
                                                                serializer: Serializer,
                                                                deserializer: Deserializer) extends AmqpJdbcScheduler[PubMsg] {
  private val logger = LoggerFactory.getLogger(getClass)

  private var ran: Boolean = false
  private var scheduledCheck: Option[Cancellable] = None
  @volatile private var currentPublishedFetchedFuture: Future[Int] = Future.successful(0)

  override def schedule(msg: Message[PubMsg], delay: FiniteDuration): Future[Unit] = {
    val serialized = serializer.serialize(msg)
    repo.save(MessageToSchedule(queueName, serialized, delay))
  }

  override def start(): Unit = {
    synchronized {
      if (!ran) {
        ran = true
        publishFetchedMessagesThanReschedule
      }
    }
  }

  private def publishFetchedMessagesThanReschedule: Future[Int] = {
    val publishedFetchedFuture = repo.fetchMessagesShouldByRun(queueName, batchSize) { messages =>
      if (messages.nonEmpty) {
        logger.debug(s"Fetched ${messages.size}, publishing")
      }
      val handlingFutures = messages.map { message =>
        val tryDeserialized = deserializer.deserialize[Message[_]](message.message)
        tryDeserialized match {
          case Success(deseralized) =>
            publisher.publish(deseralized.asInstanceOf[Message[PubMsg]])
          case Failure(ex) =>
            logger.error(s"Message ${message.message} skipped because of parse failure", ex)
            Future.successful(())
        }
      }
      Future.sequence(handlingFutures)
    }
    currentPublishedFetchedFuture = publishedFetchedFuture
    publishedFetchedFuture.onFailure {
      case NonFatal(ex) =>
        logger.error("Exception while publishing fetched messages", ex)
    }
    publishedFetchedFuture.onComplete { _ =>
      synchronized {
        if (ran) {
          scheduledCheck = Some(scheduler.scheduleOnce(checkInterval)(publishFetchedMessagesThanReschedule))
        } else {
          logger.debug(s"Scheduler is stopping, next check will be skipped")
        }
      }
    }
    publishedFetchedFuture
  }

  override def stop(): Future[Unit] = {
    synchronized {
      scheduledCheck.foreach(_.cancel())
      ran = false
    }
    currentPublishedFetchedFuture.map(_ => Unit)
  }

}