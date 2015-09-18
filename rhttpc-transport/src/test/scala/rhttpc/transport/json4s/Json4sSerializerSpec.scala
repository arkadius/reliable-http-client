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
package rhttpc.transport.json4s

import akka.http.scaladsl.model._
import org.json4s.native.Serialization._
import org.scalatest._
import org.scalatest.prop.TableDrivenPropertyChecks

class Json4sSerializerSpec extends FlatSpec with TableDrivenPropertyChecks with Matchers {
  implicit val formats = Json4sSerializer.formats

  val data = Table[HttpRequest](
    "request",
    HttpRequest().withMethod(HttpMethods.POST).withEntity("foo")
  )

  it should "work round-trip" in {
    forAll(data) { request =>
      val serialized = writePretty(request)
//      println("Serialized: " + serialized)
      withClue("Serialized: " + serialized) {
        val deserialized = read[HttpRequest](serialized)
        deserialized shouldEqual request
      }
    }
  }
}