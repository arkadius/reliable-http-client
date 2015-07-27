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

import org.slf4j.LoggerFactory
import tugboat._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import DockerEnrichments._
import dispatch._

object DeliveryResponseAfterRestartSpec extends App {
  lazy val logger = LoggerFactory.getLogger(getClass)

  val docker = Docker(hostStr = "http://localhost:4243")

  val testFuture = for {
    containerId <- docker.containerFindByNameOrCreate("test_sampleapp_1", "sampleapp:0.0.1-SNAPSHOT")
    container: docker.containers.Container = docker.containers.get(containerId)
    _ <- container.start.portBind(Port.Tcp(8081), PortBinding.local(8081))()
    _ = {
      logger.info("App started")
      container.logs.tail(0).follow(true).stdout(true).stderr(true).stream(msg => logger.debug(">>> sampleapp: " + msg))
      stopContainerOnShutdown(container, 10 seconds)
      Thread.sleep(5000)
    }
    fooBarClient = new FooBarClient(url("http://localhost:8081"), "123")
    _ <- fooBarClient.foo
  } yield {
    logger.info("Foo successful")
  }

  Await.result(testFuture, 30 seconds)

  def stopContainerOnShutdown(container: docker.containers.Container, duration: FiniteDuration) = {
    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run(): Unit = {
        logger.info(s"Graceful stopping container: ${container.id}")
        Await.result(container.stop(duration)(), duration + 5.seconds)
        logger.info(s"Container shutdown successful: ${container.id}")
      }
    })
  }
}

