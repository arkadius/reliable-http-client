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
package rhttpc.client.protocol

import java.time.{Duration, Instant}

case class WithRetryingHistory[T](msg: T, history: IndexedSeq[HistoryEntry]) {
  def withNextAttempt(timestamp: Instant, plannedDelay: Duration) = {
    copy(history = history :+ HistoryEntry(timestamp, Some(plannedDelay)))
  }
}

object WithRetryingHistory {
  def firstAttempt[T](msg: T, timestamp: Instant): WithRetryingHistory[T] = {
    WithRetryingHistory(msg, IndexedSeq(HistoryEntry(timestamp, None)))
  }
}

case class HistoryEntry(timestamp: Instant, plannedDelay: Option[Duration])