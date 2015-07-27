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
package reliablehttpc.test

import com.kolor.docker.api._
import com.kolor.docker.api.entities.ContainerConfiguration
import com.kolor.docker.api.json.Formats._
import org.slf4j.LoggerFactory
import reliablehttpc.test.DockerEnrichments._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object DeliveryResponseAfterRestartSpec extends App {
  lazy val logger = LoggerFactory.getLogger(getClass)

  implicit val docker = Docker("localhost")

  val startFuture = for {
    containerId <- docker.containerFindByNameOrCreate("test_sampleapp_1", "sampleapp:0.0.1-SNAPSHOT", ContainerConfiguration())
    started <- docker.containerStart(containerId)
  } yield {
    if (!started) {
      logger.error("Not started")
    } else {
      logger.info("sampleapp started")
    }
  }

  Await.result(startFuture, 10 seconds)
}

