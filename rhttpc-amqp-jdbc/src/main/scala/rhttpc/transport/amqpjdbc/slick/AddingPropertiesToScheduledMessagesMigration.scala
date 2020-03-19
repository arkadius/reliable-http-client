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
import slick.jdbc.{JdbcProfile, JdbcType}
import slick.sql.SqlProfile.ColumnOption.NotNull

trait AddingPropertiesToScheduledMessagesMigration extends SlickJdbcMigration {
  import profile.api._

  class V1_001__CreatingScheduledMessagesTableMigration extends CreatingScheduledMessagesTableMigration {
    override protected val profile: JdbcProfile = AddingPropertiesToScheduledMessagesMigration.this.profile
  }

  private lazy val sheduledMessagesWithoutPropsMigration = new V1_001__CreatingScheduledMessagesTableMigration

  override def migrateActions = {
    sheduledMessagesWithoutPropsMigration.scheduledMessages.schema.drop andThen
      scheduledMessages.schema.create
  }

  protected val messageMaxSize = 8192
  protected val propertiesMaxSize = 256

  import collection.JavaConverters._

  protected implicit def propertiesMapper: JdbcType[Map[String, Any]] = MappedColumnType.base[Map[String, Any], String](
    m => {
      ConfigValueFactory.fromMap(m.asJava).render(ConfigRenderOptions.concise())
    },
    str => {
      ConfigFactory.parseString(str).root().unwrapped().asScala.toMap
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