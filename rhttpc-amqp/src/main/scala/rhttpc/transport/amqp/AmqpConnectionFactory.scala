package rhttpc.transport.amqp

import akka.actor.ActorSystem
import com.rabbitmq.client.{Connection, ConnectionFactory}
import net.ceedubs.ficus.Ficus._

import scala.util._

object AmqpConnectionFactory {
  def create(actorSystem: ActorSystem): Connection = {
    val config = actorSystem.settings.config.as[AmqpConfig]("amqp")
    val factory = new ConnectionFactory()
    config.virtualHost.foreach(factory.setVirtualHost)
    config.userName.foreach(factory.setUsername)
    config.password.foreach(factory.setPassword)
    factory.setAutomaticRecoveryEnabled(true)
    retry(n = 10, delay = 5000) {
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

case class AmqpConfig(hosts: Seq[String], virtualHost: Option[String], userName: Option[String], password: Option[String])