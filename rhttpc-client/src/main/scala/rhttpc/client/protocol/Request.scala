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

import scala.concurrent.duration.FiniteDuration

case class Request[+T](correlated: Correlated[T], attemptsSoFar: Int, lastPlannedDelay: Option[FiniteDuration]) {
  def msg = correlated.msg

  def correlationId = correlated.correlationId

  def isFirst: Boolean = attemptsSoFar == 0

  def nextAttempt: Request[T] =
    copy(attemptsSoFar = attemptsSoFar + 1)
}

object Request {
  def apply[T](correlated: Correlated[T], attemptsSoFar: Int, lastPlannedDelay: FiniteDuration): Request[T] = {
    Request(
      correlated = correlated,
      attemptsSoFar = attemptsSoFar,
      lastPlannedDelay = Some(lastPlannedDelay)
    )
  }

  def firstAttempt[T](correlated: Correlated[T]): Request[T] = {
    Request(
      correlated = correlated,
      attemptsSoFar = 0,
      lastPlannedDelay = None
    )
  }
}