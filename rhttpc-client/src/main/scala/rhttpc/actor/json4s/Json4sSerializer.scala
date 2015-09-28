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
package rhttpc.actor.json4s

import java.nio.ByteBuffer
import java.nio.charset.Charset

import akka.actor.ExtendedActorSystem
import akka.serialization.Serializer
import org.json4s.native.Serialization._

class Json4sSerializer(system: ExtendedActorSystem) extends Serializer {
  import Json4sSerializer._
  import rhttpc.transport.json4s.Json4sHttpRequestResponseFormats.formats

  override def identifier: Int = ID

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifestOpt: Option[Class[_]]): AnyRef = {
    implicit val manifest = manifestOpt match {
      case Some(x) => Manifest.classType(x)
      case None    => Manifest.AnyRef
    }
    read(new String(bytes, UTF8))
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    writePretty(o).getBytes(UTF8)
  }
}

object Json4sSerializer {
  private val UTF8: Charset = Charset.forName("UTF-8")
  private val ID: Int = ByteBuffer.wrap("json4s".getBytes(UTF8)).getInt
}