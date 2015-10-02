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
package rhttpc.proxy.handler

import akka.http.scaladsl.model._
import rhttpc.proxy.HttpProxyContext
import rhttpc.transport.Publisher
import rhttpc.transport.protocol.Correlated

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util._

trait RetryingNonSuccessResponseProcessor extends HttpResponseProcessor {

  override def processResponse(response: Try[HttpResponse], ctx: HttpProxyContext): Future[Unit] = {
    (handleSuccess(ctx) orElse handleFailure(ctx))(response)
  }

  protected def handleSuccess(ctx:  HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]]

  private def handleFailure(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case Failure(ex) =>
      val delay = 1.seconds
      ctx.log.error(s"Failure message for ${ctx.correlationId}, retrying after $delay", ex)
      RetryAction(retriedRequestsPublisher, ctx)(Correlated(ctx.request, ctx.correlationId), delay)
    case nonSuccess =>
      val delay = 1.seconds
      ctx.log.error(s"Non-success message for ${ctx.correlationId}, retrying after $delay")
      RetryAction(retriedRequestsPublisher, ctx)(Correlated(ctx.request, ctx.correlationId), delay)
  }

  protected def retriedRequestsPublisher: Publisher[Correlated[HttpRequest]]

}