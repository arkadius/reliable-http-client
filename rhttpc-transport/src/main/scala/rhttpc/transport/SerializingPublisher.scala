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

import java.io.{ByteArrayOutputStream, OutputStreamWriter}

import rhttpc.transport.SerializingPublisher.SerializedMessage

import scala.concurrent.Future

trait SerializingPublisher[Msg] extends Publisher[Msg] {

  protected def serializer: Serializer[Msg]

  override def publish(msg: Message[Msg]): Future[Unit] = {
    val bos = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(bos, "UTF-8")
    try {
      writer.write(serializer.serialize(msg.content))
    } finally {
      writer.close()
    }
    publishSerialized(SerializedMessage(bos.toByteArray, msg.properties))
  }

  private[rhttpc] def publishSerialized(msg: SerializedMessage): Future[Unit]

}

object SerializingPublisher {

  case class SerializedMessage(content: Array[Byte], properties: Map[String, Any])

}