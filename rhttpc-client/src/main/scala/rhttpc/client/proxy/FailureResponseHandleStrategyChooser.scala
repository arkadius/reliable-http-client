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
package rhttpc.client.proxy

import java.time.Duration

trait FailureResponseHandleStrategyChooser {
  def choose(attempt: Int, lastPlannedDelay: Option[Duration]): ResponseHandleStrategy
}

sealed trait ResponseHandleStrategy

case class Retry(delay: Duration) extends ResponseHandleStrategy
case object SendToDLQ extends ResponseHandleStrategy
case object Skip extends ResponseHandleStrategy

object SkipAll extends FailureResponseHandleStrategyChooser {
  override def choose(attempt: Int, lastPlannedDelay: Option[Duration]): ResponseHandleStrategy = Skip
}

case class BackoffRetry(initialDelay: Duration, multiplier: BigDecimal, maxAttempts: Int) extends FailureResponseHandleStrategyChooser {
  override def choose(attempt: Int, lastPlannedDelay: Option[Duration]): ResponseHandleStrategy = {
    if (attempt == 2) {
      Retry(initialDelay)
    } else if (attempt > maxAttempts) {
      SendToDLQ
    } else {
      val nextDelay = lastPlannedDelay match {
        case Some(lastDelay) =>
          Duration.ofMillis((lastDelay.toMillis * multiplier).toLong)
        case None =>
          initialDelay
      }
      Retry(nextDelay)
    }
  }
}