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

import java.text.ParseException
import java.time.Instant
import java.util.Date

import org.json4s.JsonAST.{JString, JInt}

object InstantSerializer extends CustomSerializerWithTypeHints[Instant, JString](format => (
  {
    js =>
      format.dateFormat.parse(js.values) match {
        case Some(date) => date.toInstant
        case None => throw new ParseException(s"Could not parse date: ${js.values}", -1)
      }
  },
  {
    i =>
      JString(format.dateFormat.format(new Date(i.toEpochMilli)))
  }
))