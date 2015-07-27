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

import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import dispatch._
import org.slf4j.LoggerFactory
import reliablehttpc.test.DockerEnrichments._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object DeliveryResponseAfterRestartSpec extends App {
  lazy val logger = LoggerFactory.getLogger(getClass)

  val docker = DockerClientBuilder.getInstance("unix:///var/run/docker.sock").build()
  val containerId = docker.containerFindByNameOrCreate("test_sampleapp_1", "sampleapp:0.0.1-SNAPSHOT") { cmd =>
    val portBindings = new Ports()
    portBindings.bind(ExposedPort.tcp(8081), Ports.Binding(8081))
    cmd.withPortBindings(portBindings)
  }
  docker.startContainerCmd(containerId).exec()
  docker.attachLogging(containerId)
  docker.stopContainerOnShutdown(containerId, 10)
  logger.info("App started")
  Thread.sleep(5000)
  val fooBarClient = new FooBarClient(url("http://localhost:8081"), "123")
  Await.result(fooBarClient.foo, 10 seconds)
}