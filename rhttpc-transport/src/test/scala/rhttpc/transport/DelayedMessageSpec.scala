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
package rhttpc.transport

import java.time.Instant

import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class DelayedMessageSpec extends FlatSpec with Matchers {

  it should "extract delayed message from properties in various numeric formats" in {
    val DelayedMessage(_, fromLongDuration, _, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100L))
    fromLongDuration shouldEqual (100 millis)
    val DelayedMessage(_, fromIntDuration, _, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100))
    fromIntDuration shouldEqual (100 millis)
  }

  it should "extract delay message from properties with right timestamp" in {
    val now = Instant.parse("2018-11-30T18:35:24.00Z")
    val DelayedMessage(_, fromLongDuration, _, _now) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100L, MessagePropertiesNaming.firstAttemptTimestamp -> now.toString))
    fromLongDuration shouldEqual (100 millis)
    _now shouldEqual(now)
  }
}