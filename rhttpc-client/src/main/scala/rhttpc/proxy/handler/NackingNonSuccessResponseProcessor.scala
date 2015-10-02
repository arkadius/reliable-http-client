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

import scala.concurrent.Future
import scala.util._

trait NackingNonSuccessResponseProcessor extends HttpResponseProcessor {

  override def processResponse(response: Try[HttpResponse], ctx: HttpProxyContext): Future[Unit] = {
    (handleSuccess(ctx) orElse handleFailure(ctx))(response)
  }

  protected def handleSuccess(ctx:  HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]]

  private def handleFailure(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case Failure(ex) =>
      ctx.log.error(s"Failure message for ${ctx.correlationId}, sending NACK", ex)
      NackAction(ex)
    case nonSuccess =>
      ctx.log.error(s"Non-success message for ${ctx.correlationId}, sending NACK")
      NackAction(new IllegalArgumentException(s"Non-success message for ${ctx.correlationId}"))
  }
}