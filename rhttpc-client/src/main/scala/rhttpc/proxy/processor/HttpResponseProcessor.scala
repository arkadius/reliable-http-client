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

import akka.http.scaladsl.model.HttpResponse
import rhttpc.proxy.HttpProxyContext
import rhttpc.transport.protocol.Correlated

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