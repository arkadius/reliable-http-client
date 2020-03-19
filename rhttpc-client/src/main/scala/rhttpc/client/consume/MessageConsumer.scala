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
package rhttpc.client.consume

import akka.actor._
import akka.pattern._
import rhttpc.utils.Recovered
import Recovered._
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.{Correlated, Exchange}
import rhttpc.transport.{Deserializer, InboundQueueData, PubSubTransport, Subscriber}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

class MessageConsumer[Request, Response](subscriberForConsumer: ActorRef => Subscriber[Correlated[Exchange[Request, Response]]],
                                         handleMessage: Exchange[Request, Response] => Future[Unit])
                                        (implicit actorSystem: ActorSystem){

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case correlated: Correlated[_] =>
        try {
          handleMessage(correlated.asInstanceOf[Correlated[Exchange[Request, Response]]].msg) pipeTo sender()
        } catch {
          case NonFatal(ex) =>
            sender() ! Status.Failure(ex)
        }
    }
    
  }))


  private val subscriber = subscriberForConsumer(consumingActor)

  def start() {
    subscriber.start()
  }

  def stop(): Future[Unit] = {
    import actorSystem.dispatcher
    recoveredFuture("stopping message subscriber", subscriber.stop())
      .flatMap(_ => recoveredFuture("stopping message consumer actor", gracefulStop(consumingActor, 30 seconds).map(_ => Unit)))
  }

}

case class MessageConsumerFactory()(implicit actorSystem: ActorSystem) {
  
  private lazy val config = ConfigParser.parse(actorSystem)
  
  def create[Request, Response](handleMessage: Exchange[Request, Response] => Future[Unit],
                                batchSize: Int = config.batchSize,
                                parallelConsumers: Int = config.parallelConsumers,
                                queuesPrefix: String = config.queuesPrefix)
                               (implicit transport: PubSubTransport,
                                deserializer: Deserializer[Correlated[Exchange[Request, Response]]]): MessageConsumer[Request, Response] = {
    new MessageConsumer(prepareSubscriber(transport, batchSize, parallelConsumers, queuesPrefix), handleMessage)
  }

  private def prepareSubscriber[Request, Response](transport: PubSubTransport,
                                                   batchSize: Int,
                                                   parallelConsumers: Int,
                                                   queuesPrefix: String)
                                                  (implicit actorSystem: ActorSystem,
                                                   deserializer: Deserializer[Correlated[Exchange[Request, Response]]]):
  (ActorRef) => Subscriber[Correlated[Exchange[Request, Response]]] =
    transport.subscriber[Correlated[_]](InboundQueueData(QueuesNaming.prepareResponseQueueName(queuesPrefix), batchSize, parallelConsumers), _)
      .asInstanceOf[Subscriber[Correlated[Exchange[Request, Response]]]]

}