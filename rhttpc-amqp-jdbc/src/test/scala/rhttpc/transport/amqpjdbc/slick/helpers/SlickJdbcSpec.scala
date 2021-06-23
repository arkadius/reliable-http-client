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
package rhttpc.transport.amqpjdbc.slick.helpers

import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.scalatest.flatspec.FixtureAnyFlatSpec
import slick.jdbc.{HsqldbProfile, JdbcBackend, JdbcProfile}

trait SlickJdbcSpec extends FixtureAnyFlatSpec {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val _profile = HsqldbProfile

  protected def profile: JdbcProfile = _profile

  override protected def withFixture(test: OneArgTest): Outcome = {
    val config = ConfigFactory.load()
    val db = JdbcBackend.Database.forConfig("db", config)
    try {
      new DatabaseInitializer(db).initDatabase()
      test(createFixture(db))
    } finally {
      db.close()
    }
  }

  protected def createFixture(db: JdbcBackend.Database): FixtureParam
}