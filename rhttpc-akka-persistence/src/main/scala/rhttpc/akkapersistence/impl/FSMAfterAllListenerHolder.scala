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
package rhttpc.akkapersistence.impl

import akka.actor.FSM
import rhttpc.akkapersistence.StateSaved

private[akkapersistence] trait FSMAfterAllListenerHolder[S, D] { this: FSM[S, D] =>
  private var currentAfterAllListener: Option[RecipientWithMsg] = None

  implicit class StateExt(state: this.State) {
    def acknowledgingAfterSave() = {
      replyingAfterSave()
    }

    def replyingAfterSave(msg: Any = StateSaved) = {
      currentAfterAllListener = Some(new RecipientWithMsg(sender(), msg))
      state
    }
  }

  protected def useCurrentAfterAllListener(): Option[RecipientWithMsg] = {
    val tmp = currentAfterAllListener
    currentAfterAllListener = None
    tmp
  }
}