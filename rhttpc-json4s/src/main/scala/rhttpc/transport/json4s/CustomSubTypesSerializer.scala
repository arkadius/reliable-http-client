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

import org.json4s.reflect.TypeInfo
import org.json4s.{Formats, Serializer, _}

import scala.reflect.ClassTag

/**
  * With contrary to org.json4s.CustomSerializer it has symmetric behaviour: if partial function for deserialization
  * is not defined in values, serialization will be chained instead of Exception throwing.
  * Also it handles subtypes - not only direct instances of T
 */
class CustomSubTypesSerializer[T: Manifest, JV <: JValue: ClassTag](
  ser: Formats => (PartialFunction[JV, T], PartialFunction[T, JV])) extends Serializer[T] {

  private val Class = implicitly[Manifest[T]].runtimeClass

  def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), T] = {
    val d = ser(format)._1
    val pf: PartialFunction[(TypeInfo, JValue), T] = {
      case (TypeInfo(clazz, _), jValue: JV) if Class.isAssignableFrom(clazz) && d.isDefinedAt(jValue) =>
        d(jValue)
    }
    pf
  }

  def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    val s = ser(format)._2
    val pf: PartialFunction[Any, JValue] = {
      case obj: T if s.isDefinedAt(obj) =>
        s(obj)
    }
    pf
  }

}