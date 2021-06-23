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

import better.files._
import com.dimafeng.testcontainers.{ForEachTestContainer, GenericContainer, MultipleContainers}
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.{Logger, LoggerFactory}
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import org.testcontainers.containers.{BindMode, Network}

import java.nio.file.attribute.PosixFilePermission._
import java.util.Properties
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Random, Try}

class DeliveryResponseAfterRestartWithDockerSpec
  extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach
    with ForEachTestContainer {

  lazy val logger: Logger = LoggerFactory.getLogger(getClass)
  lazy val logConsumer = new Slf4jLogConsumer(logger)

  it should "handle response during application unavailable" in {
    val id = "123"
    await(fooBarClient.foo(id))
    await(fooBarClient.currentState(id)) shouldEqual "WaitingForResponseState"
    restartApp(waitAfterStart = 10)
    await(fooBarClient.currentState(id)) shouldEqual "FooState"
  }

  it should "handle response during application unavailable for many actors" in {
    val random = new Random()
    val max = 9
    val foos = await((0 to max).map { id =>
      val sendFoo = random.nextBoolean()
      for {
        _ <- if (sendFoo)
          fooBarClient.foo(id.toString)
        else
          fooBarClient.bar(id.toString)
      } yield sendFoo
    })
    await((0 to max).map { id =>
      fooBarClient.currentState(id.toString)
    }) shouldEqual (0 to max).map(_ => "WaitingForResponseState")

    restartApp(waitAfterStart = 10)

    await((0 to max).map { id =>
      fooBarClient.currentState(id.toString)
    }) shouldEqual (0 to max).map { id =>
      val prefix = if (foos(id)) "Foo" else "Bar"
      prefix + "State"
    }
  }

  it should "retry message if fail" in {
    val id = "123"
    await(fooBarClient.retriedFoo(id, failCount = 3))
    await(fooBarClient.currentState(id)) shouldEqual "WaitingForResponseState"
    restartApp(waitAfterStart = 3 * 5 + 2)
    await(fooBarClient.currentState(id)) shouldEqual "FooState"
  }

  it should "fallback to in memory transport when rabbitmq became unavailable" in {
    val id = "123"
    stopRabbitMq()
    await(fooBarClient.foo(id))
    await(fooBarClient.currentState(id)) shouldEqual "WaitingForResponseState"
    Thread.sleep(10 * 1000)
    await(fooBarClient.currentState(id)) shouldEqual "FooState"
  }

  def await[T](future: Future[T]): T =
    Await.result(future, 10 seconds)

  def await[T](futures: Seq[Future[T]]): Seq[T] =
    Await.result(Future.sequence(futures), 60 seconds)

  val repo = "arkadius"

  val tmpDirForAkkaPersistence: File =
    File.newTemporaryDirectory(".rhttpc-test-tmp-")
      .addPermission(OTHERS_WRITE)
      .addPermission(OTHERS_READ)
      .addPermission(OTHERS_EXECUTE)
      .deleteOnExit()

  val dockerNetwork: Network = Network.builder().driver("bridge").build()

  val rabbitMqContainer: GenericContainer =
    GenericContainer(dockerImage = "glopart/rabbitmq:latest")
      .configure(_.withNetwork(dockerNetwork))
      .configure(_.withNetworkAliases("rabbitmq"))
      .configure(_.withLogConsumer(logConsumer))

  val echoContainer: GenericContainer =
    GenericContainer(dockerImage = s"$repo/sampleecho:$appVersion")
      .configure(_.withNetwork(dockerNetwork))
      .configure(_.withNetworkAliases("sampleecho"))
      .configure(_.withLogConsumer(logConsumer))

  val appContainer: GenericContainer = GenericContainer(
    dockerImage = s"$repo/sampleapp:$appVersion",
    exposedPorts = Seq(8081),
    waitStrategy =
      new HttpWaitStrategy()
        .forPort(8081)
        .forPath("/healthcheck"))
    .configure(_.withNetwork(dockerNetwork))
    .configure(_.withLogConsumer(logConsumer))
    .configure(_.withFileSystemBind(tmpDirForAkkaPersistence.canonicalPath, "/akka-persistence", BindMode.READ_WRITE))

  def appIp: String = Try(appContainer.containerIpAddress).getOrElse(throw new Exception("App container not started yet"))

  def appPort: Int = Try(appContainer.mappedPort(8081)).getOrElse(throw new Exception("App container not started yet"))

  def appAddress = s"http://$appIp:$appPort"

  override val container: MultipleContainers = MultipleContainers(rabbitMqContainer, echoContainer, appContainer)

  private var maybeFooBarClient: Option[FooBarClient] = None

  def fooBarClient: FooBarClient = maybeFooBarClient.getOrElse(throw new Exception("FooBarClient not started yet"))

  def restartApp(waitAfterStart: Long): Unit = {
    appContainer.stop()
    appContainer.start()

    Thread.sleep(waitAfterStart * 1000)
    logger.info("App restarted")
  }

  def stopRabbitMq(): Unit = {
    rabbitMqContainer.stop()
    logger.info("RabbitMQ stopped")
  }

  override def afterStart(): Unit = {
    maybeFooBarClient = Some(new FooBarClient(dispatch.url(appAddress)))

    Thread.sleep(10 * 1000)
    logger.info("App started")
    super.afterStart()
  }

  override def afterEach(): Unit = {
    FileUtils.cleanDirectory(tmpDirForAkkaPersistence.toJava)
    super.afterEach()
  }

  override def beforeStop(): Unit = {
    super.beforeStop()
    maybeFooBarClient.foreach(_.shutdown())
  }

  private lazy val appVersion: String = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/project.properties"))
    props.getProperty("app.version")
  }
}
