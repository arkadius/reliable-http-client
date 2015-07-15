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
package reliablehttpc.sample

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class InMemDelayedEchoClient(delay: FiniteDuration)(implicit system: ActorSystem) extends DelayedEchoClient {
  import system.dispatcher

  override def requestResponse(msg: String): Future[String] = after(delay, system.scheduler)(Future.successful(msg))
}