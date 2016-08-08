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
package rhttpc.transport.argonaut

import java.text.ParseException

import _root_.argonaut.Argonaut._
import _root_.argonaut.{DecodeJson, EncodeJson, PrettyParams}
import rhttpc.transport.{Deserializer, Serializer}

import scala.util.{Failure, Success, Try}

class ArgonautSerializer[Msg: EncodeJson](implicit prettyParams: PrettyParams = defaultPrettyParams)
  extends Serializer[Msg] {

  override def serialize(msg: Msg): String = {
    msg.asJson.pretty(prettyParams)
  }
}

class ArgonautDeserializer[Msg: DecodeJson] extends Deserializer[Msg] {
  override def deserialize(value: String): Try[Msg] = {
    value.decodeWithMessage[Try[Msg], Msg](Success(_), msg => Failure(new ParseException(msg, -1)))
  }
}