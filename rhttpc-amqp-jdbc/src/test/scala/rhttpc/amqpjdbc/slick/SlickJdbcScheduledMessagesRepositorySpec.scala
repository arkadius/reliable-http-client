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

import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Millis, Span}
import rhttpc.amqpjdbc.slick.helpers.SlickJdbcSpec
import rhttpc.amqpjdbc.{ScheduledMessage, MessageToSchedule, ScheduledMessagesRepository}
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class SlickJdbcScheduledMessagesRepositorySpec extends SlickJdbcSpec with ScalaFutures with Matchers {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(3, Seconds)),
    interval = scaled(Span(100, Millis))
  )

  private final val queueName = "fooQueue"
  private final val batchSize = 10

  it should "save message" in { fixture =>
    fixture.fetchAndCheck(_ shouldBe empty)

    fixture.repo.save(MessageToSchedule(queueName, "scheduled in future", 5 second))
    fixture.fetchAndCheck(_ shouldBe empty)

    val content = "scheduled for now"
    fixture.repo.save(MessageToSchedule(queueName, content, 0 second))

    fixture.fetchAndCheck { fetched =>
      fetched should have length 1
      val msg = fetched.head
      msg.queueName shouldEqual queueName
      msg.message shouldEqual content
    }
  }

  override protected def createFixture(db: JdbcBackend.Database) = {
    FixtureParam(new SlickJdbcScheduledMessagesRepository(driver, db))
  }

  case class FixtureParam(repo: ScheduledMessagesRepository) {
    def fetchAndCheck(check: Seq[ScheduledMessage] => Unit) = {
      val fetchResult = repo.fetchMessagesShouldByRun(queueName, batchSize) { msgs =>
        check(msgs)
        Future.successful(Unit)
      }
      whenReady(fetchResult) { _ => Unit }
    }
  }

}