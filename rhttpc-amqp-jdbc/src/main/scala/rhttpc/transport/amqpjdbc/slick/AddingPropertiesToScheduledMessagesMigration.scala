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

import com.typesafe.config._
import rhttpc.transport.amqpjdbc.ScheduledMessage
import slick.driver.JdbcDriver
import slick.jdbc.JdbcType
import slick.profile.SqlProfile.ColumnOption.NotNull

import scala.language.postfixOps

trait AddingPropertiesToScheduledMessagesMigration extends SlickJdbcMigration {
  import driver.api._

  private lazy val sheduledMessagesWithoutPropsMigration = new CreatingScheduledMessagesTableMigration {
    override protected val driver: JdbcDriver = AddingPropertiesToScheduledMessagesMigration.this.driver
  }

  override def migrateActions = {
    sheduledMessagesWithoutPropsMigration.scheduledMessages.schema.drop andThen
      scheduledMessages.schema.create
  }

  protected val messageMaxSize = 8192
  protected val propertiesMaxSize = 256

  import collection.convert.wrapAll._

  protected implicit def propertiesMapper: JdbcType[Map[String, Any]] = MappedColumnType.base[Map[String, Any], String](
    m => {
      ConfigValueFactory.fromMap(m).render(ConfigRenderOptions.concise())
    },
    str => {
      ConfigFactory.parseString(str).root().unwrapped().toMap
    }
  )

  val scheduledMessages = TableQuery[ScheduledMessageEntity]

  class ScheduledMessageEntity(tag: Tag) extends Table[ScheduledMessage](tag, "scheduled_messages") {

    def id = column[Long]("id", NotNull, O.PrimaryKey, O.AutoInc)
    def queueName = column[String]("queue_name", NotNull, O.Length(64))
    def content = column[String]("content", NotNull, O.Length(messageMaxSize))
    def properties = column[Map[String, Any]]("properties", NotNull, O.Length(propertiesMaxSize))
    def plannedRun = column[Timestamp]("planned_run", NotNull)

    def * = (id.?, queueName, content, properties, plannedRun) <> (ScheduledMessage.apply _ tupled, ScheduledMessage.unapply)

    def idxQueueName = index("queue_name_idx", queueName)

    def idxQueueNamePlannedRun = index("name_planned_run_idx", (queueName, plannedRun))

  }

}