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
package rhttpc.akkahttp.json4s

import java.util.UUID

import akka.http.scaladsl.model._
import org.json4s.native.Serialization
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks
import rhttpc.client.protocol.{Correlated, Exchange, FailureExchange, SuccessExchange}
import rhttpc.client.proxy.{ExhaustedRetry, NonSuccessResponse}

class Json4sHttpRequestResponseFormatsSpec extends AnyFlatSpec with TableDrivenPropertyChecks with Matchers {
  implicit val formats = Json4sHttpRequestResponseFormats.formats

  val requestsData = Table[Correlated[HttpRequest]](
    "request",
    Correlated(HttpRequest().withMethod(HttpMethods.POST).withEntity("foo"), UUID.randomUUID().toString)
  )

  // FIXME: unignore tests when json4s problem with classloaders will be fixed (test fail only from cmd, from IDE work)
  ignore should "work round-trip for requests" in {
    forAll(requestsData) { request =>
      val serialized = Serialization.writePretty(request)
      println("Serialized: " + serialized)
      withClue("Serialized: " + serialized) {
        val deserialized = Serialization.read[Correlated[HttpRequest]](serialized)
        println("Deserialized: " + deserialized)
        deserialized shouldEqual request
      }
    }
  }

  val responsesData = Table[Correlated[Exchange[HttpRequest, HttpResponse]]](
    "responses",
    Correlated(SuccessExchange(HttpRequest(), HttpResponse().withEntity("bar")), UUID.randomUUID().toString),
    Correlated(FailureExchange(HttpRequest(), ExhaustedRetry(NonSuccessResponse)), UUID.randomUUID().toString)
  )

  ignore should "work round-trip for responses" in {
    forAll(responsesData) { response =>
      val serialized = Serialization.writePretty(response)
      println("Serialized: " + serialized)
      withClue("Serialized: " + serialized) {
        val deserialized = Serialization.read[Correlated[Exchange[HttpRequest, HttpResponse]]](serialized)
        println("Deserialized: " + deserialized)
        deserialized shouldEqual response
      }
    }
  }
}