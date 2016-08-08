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

import org.json4s.Formats
import org.json4s.native.Serialization
import rhttpc.transport.{Deserializer, Serializer}

import scala.util.Try

class Json4sSerializer[Msg <: AnyRef](implicit formats: Formats) extends Serializer[Msg] {
  override def serialize(msg: Msg): String = {
    Serialization.write(msg)(formats)
  }
}

class Json4sDeserializer[Msg: Manifest](implicit formats: Formats) extends Deserializer[Msg] {
  override def deserialize(value: String): Try[Msg] = {
    Try(Serialization.read[Msg](value))
  }
}