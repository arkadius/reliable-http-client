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

import java.lang.reflect.Constructor

import org.json4s.{CustomSerializer, Extraction, Formats, Serializer, TypeInfo}
import org.json4s.JsonAST._

import scala.util.Try

object ExceptionSerializer extends Serializer[Throwable] {
  override def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, JValue), Throwable] = {
    case (
      TypeInfo(clazz, _),
      JObject(("jsonClass", JString(ExceptionClassHavingConstructorWithMessageAndCause(constructor))) ::
        ("message", JString(message)) ::
        ("cause", cause) ::
        Nil)) if classOf[Throwable].isAssignableFrom(clazz) =>
      constructor.newInstance(message, Extraction.extract[Throwable](cause))
    case (
      TypeInfo(clazz, _),
      JObject(("jsonClass", JString(ExceptionClassHavingConstructorWithMessageOnly(constructor))) ::
        ("message", JString(message)) ::
        Nil)) if classOf[Throwable].isAssignableFrom(clazz) =>
      constructor.newInstance(message)
  }

  override def serialize(implicit formats: Formats): PartialFunction[Any, JValue] = {
    case ExceptionInstanceHavingConstructorWithMessageAndCause(ex) =>
      JObject(
        formats.typeHintFieldName -> JString(ex.getClass.getName),
        "message" -> JString(ex.getMessage),
        "cause" -> Extraction.decompose(ex.getCause)
      )
    case ExceptionInstanceHavingConstructorWithMessageOnly(ex) =>
      JObject(
        formats.typeHintFieldName -> JString(ex.getClass.getName),
        "message" -> JString(ex.getMessage)
      )
  }
}


object ExceptionClassHavingConstructorWithMessageAndCause {
  def unapply(className: String): Option[Constructor[Throwable]] = {
    (for {
      clazz <- Try(Class.forName(className))
      if classOf[Throwable].isAssignableFrom(clazz)
      constructor <- constructorWithMessageAndCause(clazz)
    } yield constructor).toOption
  }

  def constructorWithMessageAndCause(clazz: Class[_]): Try[Constructor[Throwable]] =
    Try(clazz.getDeclaredConstructor(classOf[String], classOf[Throwable]).asInstanceOf[Constructor[Throwable]])
}

object ExceptionInstanceHavingConstructorWithMessageAndCause {

  def unapply(instance: Throwable): Option[Throwable] = {
    ExceptionClassHavingConstructorWithMessageAndCause.constructorWithMessageAndCause(instance.getClass).map(_ => instance).toOption
  }

}

object ExceptionClassHavingConstructorWithMessageOnly {
  def unapply(className: String): Option[Constructor[Throwable]] = {
    (for {
      clazz <- Try(Class.forName(className))
      if classOf[Throwable].isAssignableFrom(clazz)
      constructor <- constructorWithMessageOnly(clazz)
    } yield constructor).toOption
  }

  def constructorWithMessageOnly(clazz: Class[_]): Try[Constructor[Throwable]] =
    Try(clazz.getDeclaredConstructor(classOf[String]).asInstanceOf[Constructor[Throwable]])
}

object ExceptionInstanceHavingConstructorWithMessageOnly {

  def unapply(instance: Throwable): Option[Throwable] = {
    ExceptionClassHavingConstructorWithMessageOnly.constructorWithMessageOnly(instance.getClass).map(_ => instance).toOption
  }

}