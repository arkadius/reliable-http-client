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

import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Millis, Span}
import rhttpc.transport.amqpjdbc.slick.helpers.SlickJdbcSpec
import rhttpc.transport.amqpjdbc.{ScheduledMessage, MessageToSchedule, ScheduledMessagesRepository}
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

  private final val batchSize = 10

  it should "save message" in { fixture =>
    val queueName = "fooQueue"
    fixture.fetchAndCheck(queueName)(_ shouldBe empty)

    fixture.save(MessageToSchedule(queueName, "scheduled in future", Map.empty, 5 second))
    fixture.fetchAndCheck(queueName)(_ shouldBe empty)

    val content = "scheduled for now"
    fixture.save(MessageToSchedule(queueName, content, Map.empty, 0 second))

    fixture.fetchAndCheck(queueName) { fetched =>
      fetched should have length 1
      val msg = fetched.head
      msg.queueName shouldEqual queueName
      msg.content shouldEqual content
    }
  }

  it should "save message with properties" in { fixture =>
    val queueName = "fooQueue"
    fixture.fetchAndCheck(queueName)(_ shouldBe empty)

    fixture.save(MessageToSchedule(queueName, "scheduled in future", Map.empty, 5 second))
    fixture.fetchAndCheck(queueName)(_ shouldBe empty)

    val content = "scheduled for now"
    val properties = Map[String, Any](
      "strKey" -> "strValue",
      "intKey" -> 123,
      "longKey" -> 234L
    )
    fixture.save(MessageToSchedule(queueName, content, properties, 0 second))

    fixture.fetchAndCheck(queueName) { fetched =>
      fetched should have length 1
      val msg = fetched.head
      msg.queueName shouldEqual queueName
      msg.content shouldEqual content
      msg.properties shouldEqual properties
    }
  }

  it should "provide stats" in { fixture =>
    val fooQueue = "fooQueue"
    val barQueue = "barQueue"
    fixture.save(fooQueue)
    fixture.save(fooQueue)
    fixture.save(barQueue)

    fixture.queuesStatsCheck(Set(fooQueue, barQueue)) { stats =>
      stats(fooQueue) shouldBe 2
      stats(barQueue) shouldBe 1
    }
  }

  override protected def createFixture(db: JdbcBackend.Database) = {
    FixtureParam(new SlickJdbcScheduledMessagesRepository(driver, db))
  }

  case class FixtureParam(private val repo: ScheduledMessagesRepository) {
    def fetchAndCheck(queueName: String)
                     (check: Seq[ScheduledMessage] => Unit): Unit = {
      val fetchResult = repo.fetchMessagesShouldByRun(queueName, batchSize) { msgs =>
        check(msgs)
        Future.successful(Unit)
      }
      whenReady(fetchResult) { _ => Unit }
    }

    def save(message: MessageToSchedule): Unit = {
      whenReady(repo.save(message)){ _ => Unit }
    }

    def save(queueName: String): Unit = {
      whenReady(repo.save(MessageToSchedule(queueName, "some message", Map.empty, 5 second))){ _ => Unit }
    }

    def queuesStatsCheck(queueNames: Set[String])
                        (check: Map[String, Int] => Unit): Unit = {
      whenReady(repo.queuesStats(queueNames))(check)
    }
  }

}