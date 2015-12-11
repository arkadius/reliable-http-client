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

import scala.concurrent.Future
import scala.util._

// It's gives at-least-once-delivery, with published response. If you want to be close to once, keep proxy in separate process.
trait PublishingMatchingSuccessResponseProcessor extends DelayedNackingNonSuccessResponseProcessor { self: SuccessRecognizer =>
  override protected def handleSuccess(ctx: HttpProxyContext): PartialFunction[Try[HttpResponse], Future[Unit]] = {
    case result if isSuccess.isDefinedAt(result) =>
      ctx.log.debug(s"Success message for ${ctx.correlationId}, publishing response")
      PublishAckAction(publisher, ctx)(result)
  }

  protected def publisher: Publisher[Correlated[Try[HttpResponse]], _]
}

trait PublishingEveryResponseProcessor
  extends PublishingMatchingSuccessResponseProcessor
  with AcceptingAllResults

trait PublishingSuccessResponseProcessor
  extends PublishingMatchingSuccessResponseProcessor
  with AcceptingSuccess { self: SuccessResponseRecognizer => }

trait PublishingEverySuccessResponseProcessor
  extends PublishingSuccessResponseProcessor
  with AcceptingAllSuccessResults

trait PublishingSuccessStatusInResponseProcessor
  extends PublishingSuccessResponseProcessor
  with AcceptingSuccessStatus