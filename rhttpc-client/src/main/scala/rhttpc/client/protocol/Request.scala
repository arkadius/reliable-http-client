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

import java.time.LocalDateTime

import scala.concurrent.duration.FiniteDuration

case class Request[+T](correlated: Correlated[T], attempt: Int, lastPlannedDelay: Option[FiniteDuration], receiveDate: LocalDateTime) {
  def msg = correlated.msg

  def correlationId = correlated.correlationId

  def isFirstAttempt: Boolean = attempt == 1

  def nextAttempt: Request[T] =
    copy(attempt = attempt + 1)
}

object Request {
  def apply[T](correlated: Correlated[T], attempt: Int, lastPlannedDelay: FiniteDuration): Request[T] = {
    Request(
      correlated = correlated,
      attempt = attempt,
      lastPlannedDelay = Some(lastPlannedDelay),
      receiveDate = LocalDateTime.now
    )
  }

  def firstAttempt[T](correlated: Correlated[T]): Request[T] = {
    Request(
      correlated = correlated,
      attempt = 1,
      lastPlannedDelay = None,
      receiveDate = LocalDateTime.now
    )
  }
}