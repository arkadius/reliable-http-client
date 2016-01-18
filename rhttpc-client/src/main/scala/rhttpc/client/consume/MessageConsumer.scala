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
import rhttpc.client.Recovered._
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.Correlated
import rhttpc.transport.{InboundQueueData, PubSubTransport, Subscriber}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.NonFatal

class MessageConsumer[Message](subscriberForConsumer: ActorRef => Subscriber[Correlated[Try[Message]]],
                               handleMessage: Correlated[Try[Message]] => Future[Unit])
                              (implicit actorSystem: ActorSystem){

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case correlated: Correlated[_] =>
        try {
          handleMessage(correlated.asInstanceOf[Correlated[Try[Message]]]) pipeTo sender()
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
    recovered(subscriber.stop(), "stopping message subscriber")
    recoveredFuture(gracefulStop(consumingActor, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Message consumer actor hasn't been stopped correctly")
    ), "stopping message consumer actor")
  }

}

case class MessageConsumerFactory(implicit actorSystem: ActorSystem) {

  def create[Message](handleMessage: Correlated[Try[Message]] => Future[Unit],
                      batchSize: Int = ConfigParser.parse(actorSystem).batchSize,
                      queuesPrefix: String = ConfigParser.parse(actorSystem).queuesPrefix)
                     (implicit messageSubscriberTransport: PubSubTransport[Nothing, Correlated[Try[Message]]]): MessageConsumer[Message] = {
    new MessageConsumer(prepareSubscriber(messageSubscriberTransport, batchSize, queuesPrefix), handleMessage)
  }

  private def prepareSubscriber[Message](transport: PubSubTransport[Nothing, Correlated[Try[Message]]], batchSize: Int, queuesPrefix: String)
                                        (implicit actorSystem: ActorSystem):
  (ActorRef) => Subscriber[Correlated[Try[Message]]] =
    transport.subscriber(InboundQueueData(QueuesNaming.prepareResponseQueueName(queuesPrefix), batchSize), _)

}