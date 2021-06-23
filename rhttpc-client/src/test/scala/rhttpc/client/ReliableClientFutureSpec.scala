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

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class ReliableClientFutureSpec extends TestKit(ActorSystem("ReliableClientFutureSpec")) with ReliableClientBaseSpec with Matchers {

  override protected implicit def ec: ExecutionContext = system.dispatcher
  implicit val timeout = Timeout(3 seconds)

  it should "send request and reply after subscription registration" in { fixture =>
    val sendFuture = fixture.client.send("foo").toFuture
    fixture.transport.publicationPromise.success(Unit)
    fixture.transport.replySubscriptionPromise.completeWith(Future {
      Thread.sleep(1000) // wait for registration of promise of subscription
      "bar"
    })

    Await.result(sendFuture, 5 seconds) shouldEqual "bar"
  }

//  it should "send request and reply before subscription registration" in { fixture =>
//    val sendFuture = fixture.client.send("foo").toFuture
//    fixture.transport.replySubscriptionPromise.success("bar")
//    fixture.transport.publicationPromise.success(Unit)
//
//    Await.result(sendFuture, 5 seconds) shouldEqual "bar"
//  }
//
//  it should "send request and reply with failure" in { fixture =>
//    val sendFuture = fixture.client.send("foo").toFuture
//    fixture.transport.publicationPromise.success(Unit)
//    fixture.transport.replySubscriptionPromise.failure(FailedResponse)
//
//    a[FailedResponse.type] shouldBe thrownBy {
//      Await.result(sendFuture, 5 seconds) shouldEqual "bar"
//    }
//  }
//
//  it should "send request acknowledged by failure" in { fixture =>
//    val sendFuture = fixture.client.send("foo").toFuture
//    fixture.transport.publicationPromise.failure(FailedAcknowledge)
//
//    a[NoAckException] shouldBe thrownBy {
//      Await.result(sendFuture, 5 seconds)
//    }
//  }

}