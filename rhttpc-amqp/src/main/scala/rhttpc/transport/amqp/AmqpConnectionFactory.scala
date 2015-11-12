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
package rhttpc.transport.amqp

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.rabbitmq.client.{Connection, ConnectionFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

class AmqpConnectionFactory()(implicit executionContext: ExecutionContext, retryCount: Int, delay: Duration) {

  def connect(config: AmqpConfig): Future[Connection] = Future {
    val factory = new ConnectionFactory()
    config.virtualHost.foreach(factory.setVirtualHost)
    config.userName.foreach(factory.setUsername)
    config.password.foreach(factory.setPassword)
    factory.setAutomaticRecoveryEnabled(true)
    retry(n = retryCount, delay = delay.toMillis) {
      Try {
        // Could By IOException or TimeoutException
        val addresses = config.hosts.map(com.rabbitmq.client.Address.parseAddress).toArray
        factory.newConnection(addresses)
      }
    }
  }

  private def retry[T](n: Int, delay: Long)(fn: => Try[T]): T = {
    fn match {
      case Success(x) => x
      case _ if n > 1 =>
        Thread.sleep(delay)
        retry(n - 1, delay)(fn)
      case Failure(e) => throw e
    }
  }

}

object AmqpConnectionFactory {
  private val DefaultRetryCount = 10
  private val DefaultDelay = Duration(5, TimeUnit.SECONDS)

  def create(actorSystem: ActorSystem)
           (implicit executionContext: ExecutionContext, retryCount: Int = DefaultRetryCount, delay: Duration = DefaultDelay): Future[Connection] = {
    import ArbitraryTypeReader._
    val config = actorSystem.settings.config.as[AmqpConfig]("amqp")
    new AmqpConnectionFactory().connect(config)
  }

  def createWith(config: AmqpConfig)
           (implicit executionContext: ExecutionContext, retryCount: Int = DefaultRetryCount, delay: Duration = DefaultDelay): Future[Connection] = {
    new AmqpConnectionFactory().connect(config)
  }
}

case class AmqpConfig(hosts: Seq[String], virtualHost: Option[String], userName: Option[String], password: Option[String])