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

import java.time.Instant

import scala.concurrent.duration._

trait FailureResponseHandleStrategyChooser {
  def choose(currentRetryAttempt: Int, lastPlannedDelay: Option[FiniteDuration], firstAttemptDate: Instant, now: Instant): ResponseHandleStrategy
}

sealed trait ResponseHandleStrategy

case class Retry(delay: FiniteDuration) extends ResponseHandleStrategy
case object SendToDLQ extends ResponseHandleStrategy
case object Handle extends ResponseHandleStrategy
case object Skip extends ResponseHandleStrategy

object HandleAll extends FailureResponseHandleStrategyChooser {
  override def choose(currentRetryAttempt: Int, lastPlannedDelay: Option[FiniteDuration], firstAttemptDate: Instant, now: Instant): ResponseHandleStrategy = Handle
}

object SkipAll extends FailureResponseHandleStrategyChooser {
  override def choose(currentRetryAttempt: Int, lastPlannedDelay: Option[FiniteDuration], firstAttemptDate: Instant, now: Instant): ResponseHandleStrategy = Skip
}

case class BackoffRetry(initialDelay: FiniteDuration,
                        multiplier: BigDecimal,
                        maxRetries: Int,
                        deadline: Option[FiniteDuration]) extends FailureResponseHandleStrategyChooser {
  override def choose(currentRetryAttempt: Int,
                      lastPlannedDelay: Option[FiniteDuration],
                      firstAttemptDate: Instant,
                      now: Instant): ResponseHandleStrategy = {
    val nextDelay = lastPlannedDelay match {
      case Some(lastDelay) =>
        (lastDelay.toMillis * multiplier).toLong
      case None =>
        initialDelay.toMillis
    }

    deadline match {
      case Some(duration) =>
        val jDuration = java.time.Duration.ofMillis(duration.toMillis)
        val deadlineDate = firstAttemptDate.plus(jDuration)

        if (now.plusMillis(nextDelay).isBefore(deadlineDate)) {
          BackoffRetries.chooseBasedOnRetries(currentRetryAttempt, lastPlannedDelay, initialDelay, maxRetries, nextDelay)
        } else {
          SendToDLQ
        }
      case None =>
        BackoffRetries.chooseBasedOnRetries(currentRetryAttempt, lastPlannedDelay, initialDelay, maxRetries, nextDelay)
    }
  }
}

object BackoffRetries {
  def chooseBasedOnRetries(currentRetryAttempt: Int,
                           lastPlannedDelay: Option[FiniteDuration],
                           initialDelay: FiniteDuration,
                           maxRetries: Int,
                           nextDelay: Long) = {
    if (currentRetryAttempt + 1 > maxRetries) {
      SendToDLQ
    } else if (currentRetryAttempt + 1 == 1) {
      Retry(initialDelay)
    } else {
      Retry(nextDelay.millis)
    }
  }
}