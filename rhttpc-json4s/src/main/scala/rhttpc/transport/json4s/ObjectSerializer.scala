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

import org.json4s._

import scala.reflect.runtime.universe._

object ObjectSerializer extends Serializer[Any] {
  private final val ID_FIELD = "jsonObject"

  override def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Any] = {
    case (_, JObject(List(JField(ID_FIELD, JString(className))))) =>
      val typeMirror = runtimeMirror(getClass.getClassLoader)
      val sym = typeMirror.moduleSymbol(Class.forName(className))
      typeMirror.reflectModule(sym).instance
  }

  override def serialize(implicit format: Formats): PartialFunction[Any, JValue] = {
    case obj if obj.getClass.getName.endsWith("$") =>
      JObject(ID_FIELD -> JString(obj.getClass.getName))
  }
}