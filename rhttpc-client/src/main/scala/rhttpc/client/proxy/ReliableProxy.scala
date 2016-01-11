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
package rhttpc.client.proxy

import akka.actor._
import akka.pattern._
import org.slf4j.LoggerFactory
import rhttpc.client.protocol.{HistoryEntry, Correlated, WithRetryingHistory}
import rhttpc.transport.Subscriber

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Success, Failure, Try}

abstract class ReliableProxy[Request: ClassTag, Response](subscriberForConsumer: ActorRef => Subscriber[WithRetryingHistory[Correlated[Request]]],
                                                          successRecognizer: SuccessRecognizer[Response],
                                                          failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                                                          handleResponse: Correlated[Try[Response]] => Future[Unit])
                                                         (implicit actorSystem: ActorSystem) {
  
  protected lazy val log = LoggerFactory.getLogger(getClass)

  private val InstanceOfRequest = implicitly[ClassTag[Request]]

  protected def send(request: Correlated[Request]): Future[Try[Response]]

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case WithRetryingHistory(corr@Correlated(InstanceOfRequest(request), correlationId), history) =>
        (for {
          tryResponse <- send(corr.asInstanceOf[Correlated[Request]])
          result <- tryResponse match {
            case Success(response) if successRecognizer.isSuccess(response) =>
              handleResponse(Correlated(Success(response), correlationId))
            case Success(response) =>
              log.warn(s"Response recognized as non-success for $correlationId")
              handleFailure(history, NonSuccessResponse, correlationId)
            case Failure(ex) =>
              log.error(s"Failure response for $correlationId")
              handleFailure(history, ex, correlationId)
          }
        } yield result) pipeTo sender()
    }

    private def handleFailure(history: Seq[HistoryEntry], failure: Throwable, correlationId: String): Future[Unit] = {
      val strategy = failureHandleStrategyChooser.choose(history.size, history.lastOption.flatMap(_.plannedDelay))
      strategy match {
        case Retry(delay) =>
          after(5 seconds, actorSystem.scheduler)(Future.failed(new Exception("FIXME"))) // FIXME
        case SendToDLQ =>
          handleResponse(Correlated(Failure(failure), correlationId))
          after(5 seconds, actorSystem.scheduler)(Future.failed(new Exception("FIXME"))) // FIXME
        case Skip =>
          Future.successful(Unit)
      }
    }
  }))

  private val subscriber = subscriberForConsumer(consumingActor)

  def run() {
    subscriber.run()
  }
  
  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    Try(subscriber.stop()).recover {
      case ex => log.error("Exception while stopping subscriber", ex)
    }
    gracefulStop(consumingActor, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Consuming actor hasn't been stopped correctly")
    )
  }
  
}

case object NonSuccessResponse extends Exception("Response was recognized as non-success")

case class ExhaustedRetry(cause: Throwable) extends Exception("Exhausted retry. Message will be moved to DLQ", cause)