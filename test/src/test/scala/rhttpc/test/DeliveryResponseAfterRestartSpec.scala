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
package rhttpc.test

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder
import dispatch._
import org.scalatest._
import org.slf4j.LoggerFactory
import rhttpc.test.DockerEnrichments._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

class DeliveryResponseAfterRestartSpec extends fixture.FlatSpec with Matchers with BeforeAndAfter {
  lazy val logger = LoggerFactory.getLogger(getClass)

  it should "handle response after application restart" in { fixture =>
    fixture.fooBarClient.foo
    fixture.fooBarClient.currentState shouldEqual "WaitingForResponseState"
    fixture.restartApp()
    fixture.fooBarClient.currentState shouldEqual "WaitingForResponseState"
    Thread.sleep(11000)
    fixture.fooBarClient.currentState shouldEqual "FooState"
  }

  class FixtureParam(val fooBarClient: FooBarClient)
                    (docker: DockerClient, appContainerId: String) {
    def restartApp() = {
      docker.stopContainerCmd(appContainerId).exec()
      docker.startContainerCmd(appContainerId).exec()
      docker.attachLogging(appContainerId)
      Thread.sleep(5000) // wait for start
      logger.info("App restarted")
    }
  }

  override protected def withFixture(test: OneArgTest): Outcome = {
    val config =
      new DockerClientConfigBuilder()
        .withUri("unix:///var/run/docker.sock")
        .withMaxTotalConnections(200)
        .withMaxPerRouteConnections(200)
    val docker: DockerClient = DockerClientBuilder.getInstance(config).build()
    val rabbitMqName = "rabbitmq_1"
    val echoName = "test_sampleecho_1"
    val rabbitmqContainerId = docker.containerStartFromScratch(rabbitMqName, "rabbitmq", "3.5.4")(identity)
    Thread.sleep(5000) // wait for rabbitmq
    val echoContainerId = docker.containerStartFromScratch(echoName, "sampleecho", "0.0.1-SNAPSHOT")(identity)
    val appContainerId = docker.containerStartFromScratch("test_sampleapp_1", "sampleapp", "0.0.1-SNAPSHOT") { cmd =>
      val portBindings = new Ports()
      portBindings.bind(ExposedPort.tcp(8081), Ports.Binding(8081))
      cmd.withPortBindings(portBindings)
      cmd.withLinks(
        new Link(echoName, "sampleecho"),
        new Link(rabbitMqName, "rabbitmq")
      )
    }
    Thread.sleep(5000) // wait for start
    logger.info("App started")
    val fooBarClient = new FooBarClient(url("http://localhost:8081"), "123")
    val result = test(new FixtureParam(fooBarClient)(docker, appContainerId))
    try {
      docker.stopAndRemoveContainer(appContainerId)
      docker.stopAndRemoveContainer(echoContainerId)
      docker.stopAndRemoveContainer(rabbitmqContainerId)
      docker.close()
    } catch {
      case NonFatal(ex) => logger.warn("Exception during cleanup", ex)
    }
    result
  }
}