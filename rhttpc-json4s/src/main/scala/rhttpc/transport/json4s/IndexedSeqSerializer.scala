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

import org.json4s.JsonAST.JArray
import org.json4s._
import org.json4s.reflect.TypeInfo

object IndexedSeqSerializer extends Serializer[IndexedSeq[_]] {
  def deserialize(implicit format: Formats) = {
    case (TypeInfo(clazz, optionalParamType), json) if classOf[IndexedSeq[_]].isAssignableFrom(clazz) =>
      json match {
        case JArray(elems) =>
          val paramType = optionalParamType.getOrElse(throw new MappingException("Parametrized type not known"))
          val elemTypeInfo = TypeInfo(paramType.getActualTypeArguments()(0).asInstanceOf[Class[_]], None)
          elems.map(Extraction.extract(_, elemTypeInfo)).toIndexedSeq
        case other =>
          throw new MappingException(s"Can't convert $other to IndexedSeq")
      }
  }

  def serialize(implicit format: Formats) = {
    case seq: IndexedSeq[_] => JArray(seq.map(Extraction.decompose).toList)
  }
}