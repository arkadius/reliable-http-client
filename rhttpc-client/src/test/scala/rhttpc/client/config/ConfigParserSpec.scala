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

import com._
import org.scalatest.{FlatSpec, Matchers}
import rhttpc.client.proxy.{BackoffRetry, HandleAll}

import scala.concurrent.duration._

class ConfigParserSpec extends FlatSpec with Matchers {

  it should "parse config with backoff strategy" in {
    val config = typesafe.config.ConfigFactory.parseString(
      """x {
        |  queuesPrefix = "rhttpc"
        |  batchSize = 10
        |  parallelConsumers = 1
        |  retryStrategy {
        |    initialDelay = 5 seconds
        |    multiplier = 1.2
        |    maxRetries = 3
        |  }
        |}
      """.stripMargin)

    ConfigParser.parse(config, "x") shouldEqual RhttpcConfig("rhttpc", 10, 1, BackoffRetry(5.seconds, 1.2, 3, None))
  }

  it should "parse config with publish all strategy" in {
    val config = typesafe.config.ConfigFactory.parseString(
      """x {
        |  queuesPrefix = "rhttpc"
        |  batchSize = 10
        |  parallelConsumers = 1
        |  retryStrategy = handle-all
        |}
      """.stripMargin)

    ConfigParser.parse(config, "x") shouldEqual RhttpcConfig("rhttpc", 10, 1, HandleAll)
  }

  it should "parse config with backoff with deadline strategy" in {
    val config = typesafe.config.ConfigFactory.parseString(
      """x {
        |  queuesPrefix = "rhttpc"
        |  batchSize = 10
        |  parallelConsumers = 1
        |  retryStrategy {
        |    initialDelay = 5 seconds
        |    multiplier = 1.2
        |    maxRetries = 3
        |    deadline = 5 seconds
        |  }
        |}
      """.stripMargin)

    ConfigParser.parse(config, "x") shouldEqual RhttpcConfig("rhttpc", 10, 1, BackoffRetry(5 seconds, 1.2, 3, Some(5 seconds)))
  }
}