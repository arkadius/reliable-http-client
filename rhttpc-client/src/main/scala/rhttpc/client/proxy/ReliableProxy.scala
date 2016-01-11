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

import java.time.Instant

import akka.actor._
import akka.pattern._
import org.slf4j.LoggerFactory
import rhttpc.client.protocol.{Correlated, WithRetryingHistory}
import rhttpc.transport.{DelayedMessage, Publisher, Subscriber}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

abstract class ReliableProxy[Request, Response](subscriberForConsumer: ActorRef => Subscriber[WithRetryingHistory[Correlated[Request]]],
                                                requestPublisher: Publisher[WithRetryingHistory[Correlated[Request]]],
                                                successRecognizer: SuccessRecognizer[Response],
                                                failureHandleStrategyChooser: FailureResponseHandleStrategyChooser,
                                                handleResponse: Correlated[Try[Response]] => Future[Unit])
                                               (implicit actorSystem: ActorSystem) {
  
  protected lazy val log = LoggerFactory.getLogger(getClass)

  protected def send(request: Correlated[Request]): Future[Try[Response]]

  private val consumingActor = actorSystem.actorOf(Props(new Actor {
    import context.dispatcher

    override def receive: Receive = {
      case withHistory: WithRetryingHistory[_] =>
        val castedWithHistory = withHistory.asInstanceOf[WithRetryingHistory[Correlated[Request]]]
        (for {
          tryResponse <- send(castedWithHistory.msg)
          result <- tryResponse match {
            case Success(response) if successRecognizer.isSuccess(response) =>
              log.debug(s"Success response for ${castedWithHistory.msg.correlationId}")
              handleResponse(Correlated(Success(response), castedWithHistory.msg.correlationId))
            case Success(response) =>
              log.warn(s"Response recognized as non-success for ${castedWithHistory.msg.correlationId}")
              handleFailure(castedWithHistory, NonSuccessResponse)
            case Failure(ex) =>
              log.error(s"Failure response for ${castedWithHistory.msg.correlationId}")
              handleFailure(castedWithHistory, ex)
          }
        } yield result) pipeTo sender()
    }

    private def handleFailure(withHistory: WithRetryingHistory[Correlated[Request]], failure: Throwable): Future[Unit] = {
      val strategy = failureHandleStrategyChooser.choose(withHistory.attempts, withHistory.history.lastOption.flatMap(_.plannedDelay))
      strategy match {
        case Retry(delay) =>
          log.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will retry in $delay")
          requestPublisher.publish(DelayedMessage(withHistory.withNextAttempt(Instant.now, delay), delay.toMillis millis))
        case SendToDLQ =>
          log.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will move to DLQ")
          handleResponse(Correlated(Failure(ExhaustedRetry(failure)), withHistory.msg.correlationId))
          after(5 seconds, actorSystem.scheduler)(Future.failed(new Exception("FIXME"))) // FIXME
        case Skip =>
          log.debug(s"Attempts so far: ${withHistory.attempts} for ${withHistory.msg.correlationId}, will skip")
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