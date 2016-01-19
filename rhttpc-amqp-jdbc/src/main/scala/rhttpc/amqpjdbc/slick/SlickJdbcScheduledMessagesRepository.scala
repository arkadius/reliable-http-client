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
package rhttpc.amqpjdbc.slick

import java.sql.Timestamp

import rhttpc.amqpjdbc.{MessageToSchedule, ScheduledMessage, ScheduledMessagesRepository}
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
    val action = for {
      currentTimestamp <- sql"select current_timestamp".as[Timestamp].head
      query = scheduledMessages.filter { msg =>
        msg.queueName === queueName &&
        msg.plannedRun <= currentTimestamp
      }.sortBy(_.plannedRun desc).take(batchSize)
      fetched <- query.result
      fetchedIds = fetched.flatMap(_.id)
      _ <- scheduledMessages.filter(_.id inSet fetchedIds).delete
      _ <- DBIO.from(onMessages(fetched))
    } yield fetched.size
    db.run(action.transactionally)
  }

}