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
package rhttpc.client.subscription

import java.util.concurrent.TimeoutException
import akka.actor._
import akka.pattern._
import akka.util.Timeout
import rhttpc.utils.Recovered
import Recovered._
import rhttpc.client._
import rhttpc.client.config.ConfigParser
import rhttpc.client.protocol.{Correlated, Exchange}
import rhttpc.transport.{Deserializer, InboundQueueData, PubSubTransport, QueueType, Subscriber}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure
import scala.util.control.NonFatal

trait SubscriptionManager {
  def start(): Unit

  def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit

  def stop(): Future[Unit]
}

trait WithSubscriptionManager {
  def subscriptionManager: SubscriptionManager
}

private[client] trait SubscriptionInternalManagement { self: SubscriptionManager =>
  private[subscription] def abort(subscription: SubscriptionOnResponse): Unit
}

private[subscription] class SubscriptionManagerImpl(transportSub: Subscriber[_], dispatcher: ActorRef)
                                                   (implicit ec: ExecutionContext)
  extends SubscriptionManager with PublicationHandler[ReplyFuture] with SubscriptionInternalManagement {

  override def start(): Unit = {
    transportSub.start()
  }

  override def beforePublication(correlationId: String): Unit = {
    dispatcher ! RegisterSubscriptionPromise(SubscriptionOnResponse(correlationId))
  }

  override def processPublicationAck(correlationId: String, ack: Future[Unit]): ReplyFuture = {
    val subscription = SubscriptionOnResponse(correlationId)
    val ackResultFuture = ack.map { _ =>
      RequestPublished(subscription)
    }.recover {
      case NonFatal(ex) =>
        abort(subscription)
        RequestAborted(subscription, ex)
    }
    new ReplyFutureImpl(subscription, ackResultFuture)(this)
  }

  override def confirmOrRegister(subscription: SubscriptionOnResponse, consumer: ActorRef): Unit = {
    dispatcher ! ConfirmOrRegisterSubscription(subscription, consumer)
  }

  override private[subscription] def abort(subscription: SubscriptionOnResponse): Unit = {
    dispatcher ! AbortSubscription(subscription)
  }

  override def stop(): Future[Unit] = {
    recoveredFuture("stopping subscriber", transportSub.stop())
      .flatMap(_ => recoveredFuture("stopping dispatcher actor", gracefulStop(dispatcher, 30 seconds).map(_ => ())))
  }

}

case class SubscriptionOnResponse(correlationId: String)

trait ReplyFuture {
  def pipeTo(listener: PublicationListener)
            (implicit ec: ExecutionContext): Unit

  def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any]
}

private[subscription] class ReplyFutureImpl(subscription: SubscriptionOnResponse, publicationFuture: Future[PublicationResult])
                                           (subscriptionManager: SubscriptionManager with SubscriptionInternalManagement) extends ReplyFuture {

  override def pipeTo(listener: PublicationListener)
                     (implicit ec: ExecutionContext): Unit = {
    // we can notice about promise registered in this place - message won't be consumed before RegisterSubscriptionPromise
    // in dispatcher actor because of mailbox processing in order
    listener.subscriptionPromiseRegistered(subscription)
    publicationFuture pipeTo listener.self
  }

  override def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any] = {
    import system.dispatcher
    val promise = Promise[Any]()
    val actor = system.actorOf(PromiseSubscriptionCommandsListener.props(this, promise)(subscriptionManager))
    val f = system.scheduler.scheduleOnce(timeout.duration) {
      subscriptionManager.abort(subscription)
      actor ! PoisonPill
      promise tryComplete Failure(new TimeoutException(s"Timed out on waiting on response from subscription"))
    }
    promise.future onComplete { _ => f.cancel() }
    promise.future
  }
}

case class SubscriptionManagerFactory()(implicit actorSystem: ActorSystem) {
  import actorSystem.dispatcher

  private lazy val config = ConfigParser.parse(actorSystem)
  
  def create[Msg](batchSize: Int = config.batchSize,
                  parallelConsumers: Int = config.parallelConsumers,
                  queuesPrefix: String = config.queuesPrefix,
                  queueType: QueueType = config.queueType)
                 (implicit transport: PubSubTransport,
                  deserializer: Deserializer[Correlated[Msg]]):
  SubscriptionManager with PublicationHandler[ReplyFuture] = {

    create(InboundQueueData(QueuesNaming.prepareResponseQueueName(queuesPrefix), batchSize, parallelConsumers, queueType = queueType))
  }

  private[client] def create[Msg](queueData: InboundQueueData)
                                 (implicit transport: PubSubTransport,
                                  deserializer: Deserializer[Correlated[Msg]]):
  SubscriptionManager with PublicationHandler[ReplyFuture] = {

    val dispatcherActor = actorSystem.actorOf(Props[MessageDispatcherActor]())
    val subscriber = transport.subscriber[Correlated[Msg]](queueData, dispatcherActor)
    new SubscriptionManagerImpl(subscriber, dispatcherActor)
  }
}