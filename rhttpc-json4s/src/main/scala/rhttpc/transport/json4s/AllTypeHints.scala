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

import org.json4s.TypeHints
import org.json4s.reflect.Reflector

object AllTypeHints extends TypeHints {
  override def containsHint(clazz: Class[_]): Boolean = true

  override def classFor(hint: String): Option[Class[_]] = Reflector.scalaTypeOf(hint).map(_.erasure)

  override def hintFor(clazz: Class[_]): String = clazz.getName

  override def shouldExtractHints(clazz: Class[_]): Boolean = true

  override val hints: List[Class[_]] = Nil
}