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

import scala.concurrent.duration._

case class Message[+T](content: T, properties: Map[String, Any] = Map.empty)

object DelayedMessage {
  def apply[T](content: T, delay: FiniteDuration, attempt: Int, firstAttemptTimestamp: Instant): Message[T] = {
    val props = Map(
      MessagePropertiesNaming.delayProperty -> delay.toMillis,
      MessagePropertiesNaming.attemptProperty -> attempt.toLong,
      MessagePropertiesNaming.firstAttemptTimestamp -> firstAttemptTimestamp.toEpochMilli
    )
    Message(content, properties = props)
  }

  def unapply[T](message: Message[T]): Option[(T, FiniteDuration, Int, Instant)] = {
    Option(message).collect {
      case Message(content, props) if props.contains(MessagePropertiesNaming.delayProperty) =>
        val delay = props(MessagePropertiesNaming.delayProperty).asInstanceOf[Number].longValue() millis
        val attempt = props.get(MessagePropertiesNaming.attemptProperty).map(_.asInstanceOf[Number].intValue()).getOrElse(1)
        // It might fail without getOrElse for messages of older formats without that field
        val firstAttemptTimestamp = props.get(MessagePropertiesNaming.firstAttemptTimestamp)
          .map(_.asInstanceOf[Number].longValue())
          .map(Instant.ofEpochMilli)
          .getOrElse(Instant.now())

        // RabbitMq for some reason serializes and deserializes String as a LongString (see ValueReader and ValueWriter)
        // That means that if you originally put String in message properties you might find that you are unable to take it out
        // because of a ClassCastException (LongString does not inherit from String or CharSequence etc. its a separate Interface)
        // that is why if you ever add a new string property here use _.toString instead of casting (we do not Cast to LongString to avoid weird dependencies
        // between modules, and casting to String would result in ClassCastException)

        (content, delay, attempt, firstAttemptTimestamp)
    }
  }
}