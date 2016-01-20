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
package rhttpc.client.protocol

import scala.util.{Failure, Success, Try}

sealed trait Exchange[+Request, +Response] {
  def request: Request
  def tryResponse: Try[Response]
}

case class SuccessExchange[Request, Response](request: Request, response: Response) extends Exchange[Request, Response] {
  override def tryResponse: Try[Response] = Success(response)
}

case class FailureExchange[Request](request: Request, exception: Throwable) extends Exchange[Request, Nothing] {
  override def tryResponse: Try[Nothing] = Failure(exception)
}