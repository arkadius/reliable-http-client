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
package rhttpc.transport.fallback

import scala.concurrent.duration._

object FallbackDefaults extends FallbackDefaults

trait FallbackDefaults {
  private[rhttpc] val maxFailures: Int = 1
  private[rhttpc] val callTimeout: FiniteDuration = 30.seconds
  private[rhttpc] val resetTimeout: FiniteDuration = 1.minutes
}