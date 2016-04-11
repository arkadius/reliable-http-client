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

import akka.actor.ActorSystem

import scala.concurrent.duration.FiniteDuration

package object fallback {

  implicit class FallbackBuilder(transport: PubSubTransport) {

    def withFallbackTo(fallbackTransport: PubSubTransport,
                       maxFailures: Int = FallbackDefaults.maxFailures,
                       callTimeout: FiniteDuration = FallbackDefaults.callTimeout,
                       resetTimeout: FiniteDuration = FallbackDefaults.resetTimeout)
                      (implicit system: ActorSystem): PubSubTransport with WithInstantPublisher with WithDelayedPublisher =
      new FallbackTransport(transport, fallbackTransport)(maxFailures, callTimeout, resetTimeout)
  }

}