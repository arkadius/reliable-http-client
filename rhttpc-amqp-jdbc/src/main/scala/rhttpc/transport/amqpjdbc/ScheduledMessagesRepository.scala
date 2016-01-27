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

import java.sql.Timestamp

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait ScheduledMessagesRepository {
  def save(message: MessageToSchedule): Future[Unit]

  def fetchMessagesShouldByRun(queueName: String, batchSize: Int)
                              (action: Seq[ScheduledMessage] => Future[Any]): Future[Int]

  def queuesStats(names: Set[String]): Future[Map[String, Int]]
}

case class MessageToSchedule(queueName: String, message: String, delay: FiniteDuration)

case class ScheduledMessage(id: Option[Long], queueName: String, message: String, plannedRun: Timestamp)