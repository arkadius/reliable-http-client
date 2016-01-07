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
package rhttpc.client

import java.util.UUID
import java.util.concurrent.TimeoutException

import akka.actor._
import akka.pattern._
import akka.util.Timeout
import org.slf4j.LoggerFactory
import rhttpc.client.actor.PromiseSubscriptionCommandsListener
import rhttpc.transport.Publisher
import rhttpc.client.protocol.Correlated

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.Failure

class ReliableClient[Request](subMgr: SubscriptionManager with SubscriptionInternalManagement,
                              publisher: Publisher[Correlated[Request]]) {
  private lazy val log = LoggerFactory.getLogger(getClass)

  def subscriptionManager: SubscriptionManager = subMgr

  def send(request: Request)(implicit ec: ExecutionContext): ReplyFuture = {
    val correlationId = UUID.randomUUID().toString
    val correlated = Correlated(request, correlationId)
    val subscription = SubscriptionOnResponse(correlationId)
    // we need to registerPromise before publish because message can be consumed before subscription on response registration 
    subMgr.registerPromise(subscription)
    val publicationAckFuture = publisher.publish(correlated).map { _ =>
      log.debug(s"Request: $correlationId successfully acknowledged")
      RequestPublished(subscription)
    }
    val abortingIfFailureFuture = publicationAckFuture.recover {
      case ex =>
        log.error(s"Request: $correlationId acknowledgement failure", ex)
        subMgr.abort(subscription)
        RequestAborted(subscription, ex)
    }
    new ReplyFuture(subscription, abortingIfFailureFuture)(request, subMgr)
  }

  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    recovered(subscriptionManager.stop(), "stopping subscriptionManager").map { _ =>
      publisher.close()
    }
  }
}

class ReplyFuture(subscription: SubscriptionOnResponse, publicationFuture: Future[PublicationResult])
                 (request: Any, subscriptionManager: SubscriptionManager with SubscriptionInternalManagement) {
  def pipeTo(listener: PublicationListener)(implicit ec: ExecutionContext): Unit = {
    // we can notice about promise registered in this place - message won't be consumed before RegisterSubscriptionPromise
    // in dispatcher actor because of mailbox processing in order
    listener.subscriptionPromiseRegistered(subscription)
    publicationFuture pipeTo listener.self
  }

  def toPublicationFuture(implicit ec: ExecutionContext): Future[Unit.type] = {
    publicationFuture.map {
      case RequestPublished(_) =>
        subscriptionManager.abort(subscription) // we are not interested about response so we need to clean up after registration promise
        Unit
      case RequestAborted(_, ex) =>
        throw new NoAckException(request, ex)
    }
  }

  def toFuture(implicit system: ActorSystem, timeout: Timeout): Future[Any] = {
    import system.dispatcher
    val promise = Promise[Any]()
    val actor = system.actorOf(PromiseSubscriptionCommandsListener.props(this, promise)(request, subscriptionManager))
    val f = system.scheduler.scheduleOnce(timeout.duration) {
      subscriptionManager.abort(subscription)
      actor ! PoisonPill
      promise tryComplete Failure(new TimeoutException(s"Timed out on waiting on response from subscription"))
    }
    promise.future onComplete { _ => f.cancel() }
    promise.future
  }
}

class NoAckException(request: Any, cause: Throwable) extends Exception(s"No acknowledge for request: $request", cause)