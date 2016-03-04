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
package rhttpc.client.config

import akka.actor.ActorSystem
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import rhttpc.client.proxy.{BackoffRetry, FailureResponseHandleStrategyChooser, HandleAll, SkipAll}

import scala.util.{Success, Try}

object ConfigParser {
  def parse(system: ActorSystem): RhttpcConfig = {
    parse(system.settings.config, "rhttpc")
  }

  def parse(config: Config, path: String): RhttpcConfig = {
    config.as[RhttpcConfig](path)
  }

  private implicit def failureResponseHandleStrategyChooserReader: ValueReader[FailureResponseHandleStrategyChooser] = RetryStrategyValueReader
}

object RetryStrategyValueReader extends ValueReader[FailureResponseHandleStrategyChooser] {
  override def read(config: Config, path: String): FailureResponseHandleStrategyChooser = {
    config.as[Try[String]](path) match {
      case Success("handle-all") => HandleAll
      case Success("skip-all") => SkipAll
      case _ => config.as[BackoffRetry](path)
    }
  }
}

case class RhttpcConfig(queuesPrefix: String,
                        batchSize: Int,
                        parallelConsumers: Int,
                        retryStrategy: FailureResponseHandleStrategyChooser)