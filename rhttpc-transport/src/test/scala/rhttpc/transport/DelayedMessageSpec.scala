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

import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._

class DelayedMessageSpec extends AnyFlatSpec with Matchers {

  it should "extract delayed message from properties in various numeric formats" in {
    val DelayedMessage(_, fromLongDuration, _, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100L))
    fromLongDuration shouldEqual (100 millis)
    val DelayedMessage(_, fromIntDuration, _, _) = Message("fooMsg", Map(MessagePropertiesNaming.delayProperty -> 100))
    fromIntDuration shouldEqual (100 millis)
  }

}