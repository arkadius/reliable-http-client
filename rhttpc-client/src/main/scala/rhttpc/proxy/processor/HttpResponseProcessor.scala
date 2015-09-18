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
package rhttpc.proxy.processor

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import rhttpc.proxy.HttpProxyContext
import rhttpc.transport.api.Correlated

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait HttpResponseProcessor {
  // return future of ack
  def handleResponse(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]]
  
  def orElse(other: HttpResponseProcessor): HttpResponseProcessor = new OrElseProcessor(this, other)
}

class OrElseProcessor(left: HttpResponseProcessor, right: HttpResponseProcessor) extends HttpResponseProcessor {
  override def handleResponse(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] =
    left.handleResponse(ctx) orElse right.handleResponse(ctx)
}

// It's gives at-least-once-send, fire-and-forget. If you want to be close to once, keep proxy in separate process.
object AcknowledgingEverySuccessResponseProcessor extends AcknowledgingSuccessResponseProcessor({case _ => })

// It's gives at-least-once-delivery, fire-and-forget. If you want to be close to once, keep proxy in separate process.
case class AcknowledgingSuccessResponseProcessor(isSuccess: PartialFunction[Try[HttpResponse], Unit]) extends HttpResponseProcessor {
  override def handleResponse(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case msg if isSuccess.isDefinedAt(msg) =>
      ctx.log.debug(s"Success message for ${ctx.correlationId}, sending ACK")
      AckAction()
    case Failure(ex) =>
      ctx.log.error(s"Failure message for ${ctx.correlationId}, sending NACK", ex)
      NackAction(ex)
    case other =>
      ctx.log.error(s"Failure message for ${ctx.correlationId}, sending NACK")
      NackAction(new IllegalArgumentException(s"Failure message: $other"))
  }
}

// Useful if you want to be close to exact-one-delivery to not idempotent external systems. You should handle if there
// will be no acknowledge for published response e.g. by some kind of dead letter. Also it will be better if proxy
// will be in external process which will be exited rearly
case class PublishingEveryResponseWithoutWaitingOnAckProcessor(onPublishAckFailure: FailureWithRequest => Unit) extends HttpResponseProcessor {
  override def handleResponse(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case every =>
      import ctx.executionContext
      PublishAckAction(ctx)(every).onFailure {
        case ex => onPublishAckFailure(FailureWithRequest(ex, ctx.request, ctx.correlationId))
      }
      Future.successful(Unit)
  }
}

case class FailureWithRequest(failure: Throwable, req: HttpRequest, correlationId: String)

// It's gives at-least-once-send. If you want to be close to once, keep proxy in separate process.
object PublishingEveryResponseProcessor extends PublishingSuccessResponseProcessor({case _ => })

// It's gives at-least-once-delivery. If you want to be close to once, keep proxy in separate process.
case class PublishingSuccessResponseProcessor(isSuccess: PartialFunction[Try[HttpResponse], Unit]) extends HttpResponseProcessor {
  override def handleResponse(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case msg if isSuccess.isDefinedAt(msg) =>
      ctx.log.debug(s"Success message for ${ctx.correlationId}, sending ACK")
      PublishAckAction(ctx)(msg)
    case Failure(ex) =>
      ctx.log.error(s"Failure message for ${ctx.correlationId}, sending NACK", ex)
      NackAction(ex)
    case other =>
      ctx.log.error(s"Failure message for ${ctx.correlationId}, sending NACK")
      NackAction(new IllegalArgumentException(s"Failure message: $other"))
  }
}

object AckAction {
  def apply(): Future[Unit] = {
    Future.successful(Unit)
  }
}

object NackAction {
  def apply(cause: Throwable): Future[Unit] = {
    Future.failed(cause)
  }
}

case class PublishAckAction(ctx: HttpProxyContext) {
  def apply(response: Try[HttpResponse]): Future[Unit] = {
    val ackFuture = ctx.publisher.publish(Correlated(response, ctx.correlationId))
    import ctx.executionContext
    ackFuture.onComplete {
      case Success(_) => ctx.log.debug(s"Publishing of message for ${ctx.correlationId} successfully acknowledged")
      case Failure(ex) => ctx.log.error(s"Publishing of message for ${ctx.correlationId} acknowledgement failed", ex)
    }
    ackFuture
  }
}