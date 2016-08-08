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
package rhttpc.transport

import _root_.argonaut.PrettyParams
import _root_.argonaut.EncodeJson
import _root_.argonaut.DecodeJson

package object argonaut {

  implicit def serializer[Msg](implicit encodeJson: EncodeJson[Msg],
                               prettyParams: PrettyParams = defaultPrettyParams): Serializer[Msg] =
    new ArgonautSerializer[Msg]()(encodeJson, prettyParams)

  implicit def deserializer[Msg: DecodeJson]: Deserializer[Msg] =
    new ArgonautDeserializer[Msg]()

  private[argonaut] val defaultPrettyParams: PrettyParams =
    PrettyParams
      .spaces2
      .copy(preserveOrder = true, dropNullKeys = true)

}