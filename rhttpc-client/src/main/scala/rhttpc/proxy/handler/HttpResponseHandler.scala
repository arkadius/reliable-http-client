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

import akka.http.scaladsl.model.HttpRequest

trait HttpResponseHandler {
  def handleForRequest: PartialFunction[HttpRequest, HttpResponseProcessor]

  final def orElse(other: HttpResponseHandler): HttpResponseHandler = new OrElseProcessor(this, other)
}

class OrElseProcessor(left: HttpResponseHandler, right: HttpResponseHandler) extends HttpResponseHandler {
  override def handleForRequest: PartialFunction[HttpRequest, HttpResponseProcessor] =
    left.handleForRequest orElse right.handleForRequest
}

class EveryResponseHandler(processor: HttpResponseProcessor) extends HttpResponseHandler {
  override def handleForRequest: PartialFunction[HttpRequest, HttpResponseProcessor] = {
    case _ => processor
  }
}