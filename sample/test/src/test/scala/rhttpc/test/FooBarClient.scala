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
package rhttpc.test

import dispatch.{Future => DispatchFuture, _}

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps

class FooBarClient(baseUrl: Req) {
  implicit val successPredicate = new retry.Success[Unit.type](_ => true)

  def foo(id: String)(implicit ec: ExecutionContext): Future[Any] =
    Http(baseUrl / id << "foo")

  def bar(id: String)(implicit ec: ExecutionContext): Future[Any] =
    Http(baseUrl / id << "bar")

  def currentState(id: String)(implicit ec: ExecutionContext): Future[String] =
    Http(baseUrl / id OK as.String)

  def retriedFoo(id: String, failCount: Int)(implicit ec: ExecutionContext): Future[Any] =
    Http(baseUrl / id << s"fail-$failCount-times-than-reply-with-foo")

}