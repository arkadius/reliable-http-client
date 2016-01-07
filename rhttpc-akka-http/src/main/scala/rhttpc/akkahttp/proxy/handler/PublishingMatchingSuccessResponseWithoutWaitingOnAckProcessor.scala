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
package rhttpc.akkahttp.proxy.handler

import akka.http.scaladsl.model._
import rhttpc.akkahttp.proxy.HttpProxyContext
import rhttpc.transport.Publisher
import rhttpc.transport.protocol.Correlated

import scala.concurrent.Future
import scala.util._

trait PublishingMatchingSuccessResponseWithoutWaitingOnAckProcessor extends DelayedNackingNonSuccessResponseProcessor { self: SuccessRecognizer =>
  override protected def handleSuccess(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case result if isSuccess.isDefinedAt(result) =>
      ctx.log.debug(s"Success message for ${ctx.correlationId}, publishing response")
      import ctx.executionContext
      PublishAckAction(publisher, ctx)(result).onFailure {
        case ex => onPublishAckFailure(FailureWithRequest(ex, ctx.request, ctx.correlationId))
      }
      Future.successful(Unit)
  }
  
  protected def onPublishAckFailure(req: FailureWithRequest): Unit

  protected def publisher: Publisher[Correlated[Try[HttpResponse]]]
}

case class FailureWithRequest(failure: Throwable, req: HttpRequest, correlationId: String)

// Useful if you want to be close to exact-one-delivery to not idempotent external systems. You should handle if there
// will be no acknowledge for published response e.g. by some kind of dead letter. Also it will be better if proxy
// will be in external process which will be highly available
trait PublishingEveryResponseWithoutWaitingOnAckProcessor
  extends PublishingMatchingSuccessResponseWithoutWaitingOnAckProcessor
  with AcceptingAllResults

trait PublishingSuccessResponseWithoutWaitingOnAckProcessor
  extends PublishingMatchingSuccessResponseWithoutWaitingOnAckProcessor
  with AcceptingSuccess { self: SuccessResponseRecognizer => }

trait PublishingEverySuccessResponseWithoutWaitingOnAckProcessor
  extends PublishingSuccessResponseWithoutWaitingOnAckProcessor
  with AcceptingAllSuccessResults

trait PublishingSuccessStatusInResponseWithoutWaitingOnAckProcessor
  extends PublishingSuccessResponseWithoutWaitingOnAckProcessor
  with AcceptingSuccessStatus