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
import slick.driver.JdbcDriver
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

private[amqpjdbc] class SlickJdbcScheduledMessagesRepository(driver: JdbcDriver, db: JdbcBackend.Database)
                                                            (implicit ec: ExecutionContext) extends ScheduledMessagesRepository {

  val messagesMigration = new CreatingScheduledMessagesTableMigration {
    override protected val driver: JdbcDriver = SlickJdbcScheduledMessagesRepository.this.driver
  }

  import messagesMigration._
  import driver.api._

  override def save(msg: MessageToSchedule): Future[Unit] = {
    import msg._
    val action = for {
      currentTimestamp <- sql"select current_timestamp".as[Timestamp].head
      plannedRun = new Timestamp(currentTimestamp.getTime + msg.delay.toMillis)
      messageToAdd = ScheduledMessage(None, queueName, message, plannedRun)
      insertResult <- scheduledMessages += messageToAdd
    } yield ()
    db.run(action.transactionally)
  }

  override def fetchMessagesShouldByRun(queueName: String, batchSize: Int)
                                       (onMessages: (Seq[ScheduledMessage]) => Future[Any]): Future[Int] = {
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
            DBIO.successful(Unit)
          }
        }
        _ <- DBIO.from(onMessages(fetched))
      } yield fetched.size
    }

    val consumedFuture = for {
      fetched <- db.run(fetchAction.transactionally)
      consumed <- db.run(consumeAction(fetched).transactionally)
    } yield consumed

    consumedFuture.recover {
      case ConcurrentFetchException => 0
    }
    consumedFuture
  }

  override def queuesStats: Future[Map[String, Int]] = {
    val action = scheduledMessages.groupBy(_.queueName).map {
      case (queueName, msgs) =>
        (queueName, msgs.size)
    }.result
    db.run(action).map(_.toMap)
  }
}

case object ConcurrentFetchException extends Exception(s"Concurrent fetch detected")