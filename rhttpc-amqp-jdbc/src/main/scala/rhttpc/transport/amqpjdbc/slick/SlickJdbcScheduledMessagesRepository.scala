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
package rhttpc.transport.amqpjdbc.slick

import java.sql.Timestamp

import rhttpc.transport.amqpjdbc.{MessageToSchedule, ScheduledMessage, ScheduledMessagesRepository}
import slick.jdbc.{JdbcBackend, JdbcProfile}

import scala.concurrent.{ExecutionContext, Future}

private[amqpjdbc] class SlickJdbcScheduledMessagesRepository(profile: JdbcProfile, db: JdbcBackend.Database)
                                                            (implicit ec: ExecutionContext) extends ScheduledMessagesRepository {

  class V1_001__AddingPropertiesToScheduledMessagesMigration extends AddingPropertiesToScheduledMessagesMigration {
    override protected val profile: JdbcProfile = SlickJdbcScheduledMessagesRepository.this.profile
  }

  val messagesMigration = new V1_001__AddingPropertiesToScheduledMessagesMigration

  import messagesMigration._
  import profile.api._

  override def save(msg: MessageToSchedule): Future[Unit] = {
    import msg._
    val action = for {
      currentTimestamp <- sql"select current_timestamp".as[Timestamp].head
      plannedRun = new Timestamp(currentTimestamp.getTime + msg.delay.toMillis)
      messageToAdd = ScheduledMessage(None, queueName, content, properties, plannedRun)
      insertResult <- scheduledMessages += messageToAdd
    } yield ()
    db.run(action.transactionally)
  }

  override def fetchMessagesShouldByRun(queueName: String, batchSize: Int)
                                       (onMessages: (Seq[ScheduledMessage]) => Future[Any]): Future[Int] = {
    def drain(): Future[Int] = {
      val fetchAction = for {
        currentTimestamp <- sql"select current_timestamp".as[Timestamp].head
        fetched <- scheduledMessages.filter { msg =>
          msg.queueName === queueName &&
            msg.plannedRun <= currentTimestamp
        }.sortBy(_.plannedRun desc).take(batchSize).result
      } yield fetched

      def consumeAction(fetched: Seq[ScheduledMessage]) = {
        val fetchedIds = fetched.flatMap(_.id)
        for {
          deleted <- scheduledMessages.filter(_.id inSet fetchedIds).delete
          _ <- {
            if (deleted != fetched.size) {
              DBIO.failed(ConcurrentFetchException)
            } else {
              DBIO.successful(())
            }
          }
          _ <- DBIO.from(onMessages(fetched))
        } yield fetched.size
      }

      val consumedFuture = for {
        fetched <- db.run(fetchAction.transactionally)
        consumed <- db.run(consumeAction(fetched).transactionally)
      } yield consumed

      val consumedRecovered = consumedFuture.recover {
        case ConcurrentFetchException => 0
      }

      for {
        consumed <- consumedRecovered
        consumedNext <- {
          if (consumed == batchSize)
            drain()
          else
            Future.successful(0)
        }
      } yield consumed + consumedNext
    }
    drain()
  }

  override def queuesStats(names: Set[String]): Future[Map[String, Int]] = {
    val action = scheduledMessages
      .filter(_.queueName inSet names)
      .groupBy(_.queueName).map {
      case (queueName, msgs) =>
        (queueName, msgs.size)
    }.result
    db.run(action).map(_.toMap)
  }
}

case object ConcurrentFetchException extends Exception(s"Concurrent fetch detected")