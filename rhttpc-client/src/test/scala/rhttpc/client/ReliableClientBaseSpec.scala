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
package rhttpc.client

import akka.testkit.TestKit
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

trait ReliableClientBaseSpec extends fixture.FlatSpecLike { self: TestKit =>

  protected implicit def ec: ExecutionContext = system.dispatcher

  case class FixtureParam(client: InOutReliableClient[String], transport: MockTransport)

  override protected def withFixture(test: OneArgTest): Outcome = {
    implicit val transport = new MockTransport((cond: () => Boolean) => awaitCond(cond()))
    val client = ReliableClientFactory().create(transport)
    try {
      test(FixtureParam(client, transport))
    } finally {
      Await.result(client.close(), 10 seconds)
    }
  }

}

case object FailedAcknowledge extends Exception("failed acknowledge")

case object FailedResponse extends Exception("failed response")