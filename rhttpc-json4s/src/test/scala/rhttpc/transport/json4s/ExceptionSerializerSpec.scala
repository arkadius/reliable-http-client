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

import org.json4s.{DefaultFormats, TypeHints}
import org.scalatest.{FlatSpec, Matchers, TryValues}

class ExceptionSerializerSpec extends FlatSpec with Matchers with TryValues {

  it should "round-trip serialize case class exception" in {
    roundTrip(CaseClassException(123))
  }

  it should "round-trip serialize exception with message" in {
    roundTrip(new ExceptionWithMessage("foo"))
  }

  it should "round-trip serialize exception with null message" in {
    roundTrip(new ExceptionWithMessage(null))
  }

  it should "round-trip serialize exception with message and cause" in {
    roundTrip(new ExceptionWithMessageAndCause("foo", CaseClassException(123)))
  }

  private def roundTrip(ex: Throwable): Unit = {
    implicit val formats = new DefaultFormats {
      override val typeHints: TypeHints = AllTypeHints
    } + ExceptionSerializer
    val serializer = new Json4sSerializer()
    val deserializer = new Json4sDeserializer()
    val serialized = serializer.serialize(ex)
    val deserialized = deserializer.deserialize[Throwable](serialized)
    deserialized.success.value shouldEqual ex
  }

}

case class CaseClassException(x: Int) extends Exception(s"x: $x")

class ExceptionWithMessage(msg: String) extends Exception(msg) {
  def canEqual(other: Any): Boolean = other.isInstanceOf[ExceptionWithMessage]

  override def equals(other: Any): Boolean = other match {
    case that: ExceptionWithMessage =>
      (that canEqual this) &&
        getMessage == that.getMessage
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(getMessage)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

class ExceptionWithMessageAndCause(msg: String, cause: Throwable) extends Exception(msg, cause) {
  def canEqual(other: Any): Boolean = other.isInstanceOf[ExceptionWithMessageAndCause]

  override def equals(other: Any): Boolean = other match {
    case that: ExceptionWithMessageAndCause =>
      (that canEqual this) &&
        getMessage == that.getMessage &&
        getCause == that.getCause
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(getMessage, getCause)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}