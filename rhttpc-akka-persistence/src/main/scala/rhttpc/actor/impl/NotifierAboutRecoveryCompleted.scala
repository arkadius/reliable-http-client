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
package rhttpc.actor.impl

import akka.actor._
import akka.persistence.RecoveryCompleted

private[actor] trait NotifierAboutRecoveryCompleted { this: AbstractSnapshotter with ActorLogging  =>

  private var recoveryCompleted: Boolean = false
  private var waitingForRecoveryCompleted: List[ActorRef] = List.empty

  protected val handleRecoveryCompleted: Receive = {
    case RecoveryCompleted =>
      log.info("Recovery completed for: " + persistenceId)
      recoveryCompleted = true
      waitingForRecoveryCompleted.foreach(_ ! RecoveryCompleted)
      waitingForRecoveryCompleted = List.empty
  }

  protected val handleNotifyAboutRecoveryCompleted: Receive = {
    case NotifyAboutRecoveryCompleted =>
      if (recoveryCompleted)
        sender() ! RecoveryCompleted
      else
        waitingForRecoveryCompleted = sender() :: waitingForRecoveryCompleted
  }

}

case object NotifyAboutRecoveryCompleted