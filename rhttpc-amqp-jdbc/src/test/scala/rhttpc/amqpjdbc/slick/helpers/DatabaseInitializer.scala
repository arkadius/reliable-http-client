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
package rhttpc.amqpjdbc.slick.helpers

import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

import com.typesafe.config.Config
import org.flywaydb.core.Flyway
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext

class DatabaseInitializer(db: JdbcBackend.Database) {
  def initDatabase()(implicit executionContext: ExecutionContext) = {
    migrateIfNeeded(db)
    db
  }

  private def migrateIfNeeded(db: JdbcBackend.Database) = {
    val flyway = new Flyway()
    flyway.setDataSource(new DatabaseDataSource(db))
    flyway.setBaselineOnMigrate(true)
    flyway.migrate()
  }
}

object DatabaseInitializer {
  def apply(config: Config) = {
    val db = JdbcBackend.Database.forConfig("db", config)
    new DatabaseInitializer(db)
  }
}

class DatabaseDataSource(db: JdbcBackend.Database) extends DataSource {
  private val conn = db.createSession().conn

  override def getConnection: Connection = conn
  override def getConnection(username: String, password: String): Connection = conn
  override def unwrap[T](iface: Class[T]): T = conn.unwrap(iface)
  override def isWrapperFor(iface: Class[_]): Boolean = conn.isWrapperFor(iface)

  override def setLogWriter(out: PrintWriter): Unit = ???
  override def getLoginTimeout: Int = ???
  override def setLoginTimeout(seconds: Int): Unit = ???
  override def getParentLogger: Logger = ???
  override def getLogWriter: PrintWriter = ???
}