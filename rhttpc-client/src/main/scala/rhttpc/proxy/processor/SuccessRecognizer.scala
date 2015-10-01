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

import scala.util.{Success, Try}

trait SuccessRecognizer {
  protected def isSuccess: PartialFunction[Try[HttpResponse], Unit]
}

trait AcceptingAllResults extends SuccessRecognizer {
  override protected def isSuccess: PartialFunction[Try[HttpResponse], Unit] = {case _ => }
}

trait AcceptingSuccess extends SuccessRecognizer { self: SuccessResponseRecognizer =>
  override protected def isSuccess: PartialFunction[Try[HttpResponse], Unit] = {
    case Success(resp) if isSuccessResponse.isDefinedAt(resp) =>
  }
}

trait SuccessResponseRecognizer {
  protected def isSuccessResponse: PartialFunction[HttpResponse, Unit]
}

trait AcceptingAllSuccessResults extends SuccessResponseRecognizer {
  override protected def isSuccessResponse: PartialFunction[HttpResponse, Unit] = {case _ => }
}

trait AcceptingSuccessStatus extends SuccessResponseRecognizer {
  override protected def isSuccessResponse: PartialFunction[HttpResponse, Unit] = {
    case resp if resp.status.isSuccess() =>
  }
}