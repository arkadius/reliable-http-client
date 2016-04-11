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
package rhttpc.transport.inmem

import scala.concurrent.duration.{FiniteDuration, _}

object InMemDefaults extends InMemDefaults

trait InMemDefaults {
  private[rhttpc] val createTimeout: FiniteDuration = 5.seconds

  private[rhttpc] val consumeTimeout: FiniteDuration = 5.minutes

  private[rhttpc] val retryDelay: FiniteDuration = 10.seconds

  private[rhttpc] val stopConsumingTimeout: FiniteDuration = 5.seconds

  private[rhttpc] val stopTimeout: FiniteDuration = 5.seconds
}