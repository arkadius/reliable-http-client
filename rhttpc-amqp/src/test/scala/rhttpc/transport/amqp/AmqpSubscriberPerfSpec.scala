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

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.testkit.{TestKit, TestProbe}
import dispatch.url
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Ignore}
import rhttpc.transport.{Deserializer, InboundQueueData, OutboundQueueData, Serializer}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Random, Try}

@Ignore
class AmqpSubscriberPerfSpec extends TestKit(ActorSystem("AmqpSubscriberPerfSpec")) with FlatSpecLike with BeforeAndAfterAll {
  import system.dispatcher

  implicit val materializer = ActorMaterializer()

  implicit def serializer[Msg] = new Serializer[Msg] {
    override def serialize(obj: Msg): String = obj.toString
  }

  implicit def deserializer[Msg] = new Deserializer[Msg] {
    override def deserialize(value: String): Try[Msg] = Try(value.asInstanceOf[Msg])
  }

  val queueName = "request"
  val outboundQueueData = OutboundQueueData(queueName, autoDelete = true, durability = false)
  val inboundQueueData = InboundQueueData(queueName, batchSize = 10, parallelConsumers = 10, autoDelete = true, durability = false)
  val count = 100

  private val interface = "localhost"
  private val port = 8081

  def handle(request: HttpRequest) = {
    val delay = 5 + Random.nextInt(10)
    after(delay.seconds, system.scheduler)(Future.successful(HttpResponse()))
  }

  it should "have a good throughput" in {
    val bound = Await.result(
      Http().bindAndHandleAsync(
        handle, interface, port
      ),
      5.seconds
    )
    val http = dispatch.Http.default
//      .configure(_.setMaxConnections(count)
//        .setExecutorService(Executors.newFixedThreadPool(count)))

    val connection = Await.result(AmqpConnectionFactory.connect(system), 5 seconds)
    val transport = AmqpTransport(
      connection = connection
    )
    val publisher = transport.publisher[String](outboundQueueData)
    val probe = TestProbe()
    val actor = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case str: String =>
          http(url(s"http://$interface:$port") OK identity).map(_ => Done).pipeTo(self)(sender())
        case Done =>
          probe.ref ! Done
          sender() ! Done
      }
    }))
    val subscriber = transport.subscriber[String](inboundQueueData, actor)
    subscriber.start()

    try {
      measureMeanThroughput(count) {
        (1 to count).foreach { _ => publisher.publish("x") }

        probe.receiveWhile(10 minutes, messages = count) { case a => a }
      }
    } finally {
      Await.result(subscriber.stop(), 5.seconds)
      connection.close(5 * 1000)
      Await.result(bound.unbind(), 5.seconds)
    }
  }

  def measureMeanThroughput(count: Int)(consume: => Unit) = {
    val before = System.currentTimeMillis()
    consume
    val msgsPerSecond = count / ((System.currentTimeMillis() - before).toDouble / 1000)
    println(s"Throughput was: $msgsPerSecond msgs/sec")
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}