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

import java.util.Properties

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder
import org.scalatest._
import org.slf4j.LoggerFactory
import rhttpc.test.DockerEnrichments._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random
import scala.util.control.NonFatal

class DeliveryResponseAfterRestartWithDockerSpec extends fixture.FlatSpec with Matchers with BeforeAndAfter {
  lazy val logger = LoggerFactory.getLogger(getClass)

  it should "handle response during application unavailable" in { fixture =>
    val id = "123"
    await(fixture.fooBarClient.foo(id))
    await(fixture.fooBarClient.currentState(id)) shouldEqual "WaitingForResponseState"
    fixture.restartApp(waitForReply = 10)
    await(fixture.fooBarClient.currentState(id)) shouldEqual "FooState"
  }

  it should "handle response during application unavailable for many actors" in { fixture =>
    val random = new Random()
    val max = 9
    val foos = await((0 to max).map { id =>
      val sendFoo = random.nextBoolean()
      for {
      _ <-if (sendFoo)
          fixture.fooBarClient.foo(id.toString)
        else
          fixture.fooBarClient.bar(id.toString)
      } yield sendFoo
    })
    await((0 to max).map { id =>
      fixture.fooBarClient.currentState(id.toString)
    }) shouldEqual (0 to max).map(_ => "WaitingForResponseState")

    fixture.restartApp(waitForReply = 10)

    await((0 to max).map { id =>
      fixture.fooBarClient.currentState(id.toString)
    }) shouldEqual (0 to max).map { id =>
      val prefix = if (foos(id)) "Foo" else "Bar"
      prefix + "State"
    }
  }

  it should "retry message if fail" in { fixture =>
    val id = "123"
    await(fixture.fooBarClient.retriedFoo(id, retryCount = 3))
    await(fixture.fooBarClient.currentState(id)) shouldEqual "WaitingForResponseState"
    fixture.restartApp(waitForReply = 3*5+2)
    await(fixture.fooBarClient.currentState(id)) shouldEqual "FooState"
  }

  def await[T](future: Future[T]): T =
    Await.result(future, 10 seconds)

  def await[T](futures: Seq[Future[T]]): Seq[T] =
    Await.result(Future.sequence(futures), 60 seconds)

  class FixtureParam(val fooBarClient: FooBarClient)
                    (docker: DockerClient, appContainerId: String) {
    def restartApp(waitForReply: Long) = {
      docker.stopContainerCmd(appContainerId).exec()
      Thread.sleep(waitForReply * 1000)
      docker.startContainerCmd(appContainerId).exec()
      docker.attachLogging(appContainerId)
      HttpProbe(appHealthCheckUrl).await()
      logger.info("App restarted")
    }
  }

  val repo = "arkadius"
  val rabbitMqName = "rabbitmq_1"
  val echoName = "test_sampleecho_1"
  val appPort = 8081
  val appHealthCheckUrl = s"http://localhost:$appPort/healthcheck"

  override protected def withFixture(test: OneArgTest): Outcome = {
    val config =
      new DockerClientConfigBuilder()
        .withUri("unix:///var/run/docker.sock")
        .withMaxTotalConnections(200)
        .withMaxPerRouteConnections(200)
    implicit val docker: DockerClient = DockerClientBuilder.getInstance(config).build()

    val rabbitmqContainerId = startRabbitMq()

    val (echoContainerId, rhttpcServerContainerId, appContainerId) = startServices()

    val fooBarClient = new FooBarClient(dispatch.url("http://localhost:8081"))
    val result = test(new FixtureParam(fooBarClient)(docker, appContainerId))
    stopAndRemoveContainers(rabbitmqContainerId, echoContainerId, rhttpcServerContainerId, appContainerId)
    result
  }

  private def startRabbitMq()(implicit docker: DockerClient): String = {
    val mgmtPort = 15672
    val rabbitmqContainerId = docker.containerStartFromScratch(rabbitMqName, "rabbitmq", "3.5.4-management") { cmd =>
      val portBindings = new Ports()
      portBindings.bind(ExposedPort.tcp(mgmtPort), Ports.Binding(mgmtPort)) // management
      cmd.withPortBindings(portBindings)
    }
    HttpProbe(s"http://localhost:$mgmtPort").await()
    logger.info("RabbitMQ started")
    rabbitmqContainerId
  }

  private def startServices()(implicit docker: DockerClient): (String, String, String) = {
    val echoContainerId = docker.containerStartFromScratch(echoName, s"$repo/sampleecho", appVersion)(identity)
    val rhttpcServerContainerId = docker.containerStartFromScratch("test_rhttpcproxy_1", s"$repo/rhttpc-proxy", appVersion) { cmd =>
      cmd.withLinks(
        new Link(echoName, "sampleecho"),
        new Link(rabbitMqName, "rabbitmq")
      )
    }
    val appContainerId = docker.containerStartFromScratch("test_sampleapp_1", s"$repo/sampleapp", appVersion) { cmd =>
      val portBindings = new Ports()
      portBindings.bind(ExposedPort.tcp(appPort), Ports.Binding(appPort))
      cmd.withPortBindings(portBindings).withLinks(
        new Link(rabbitMqName, "rabbitmq")
      )
    }
    HttpProbe(appHealthCheckUrl).await()
    logger.info("App started")
    (echoContainerId, rhttpcServerContainerId, appContainerId)
  }

  private lazy val appVersion: String = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/project.properties"))
    props.getProperty("app.version")
  }

  private def stopAndRemoveContainers(constainerIds: String*)
                                     (implicit docker: DockerClient): Unit = {

    constainerIds.toList.reverse.foreach { containerId =>
      try {
        docker.stopAndRemoveContainer(containerId)
      } catch {
        case NonFatal(ex) => logger.warn(s"Exception during cleanup after container with id: $containerId", ex)
      }
    }
    try {
      docker.close()
    } catch {
      case NonFatal(ex) => logger.warn("Exception during closing docker", ex)
    }
  }
}