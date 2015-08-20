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
package rhttpc.proxy

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import com.spingo.op_rabbit.{MessagePublisher, RabbitHelpers}

case class QueuePublisherDeclaringQueueIfNotExist(queue: String) extends MessagePublisher {
  private var verified = false
  def apply(c: Channel, data: Array[Byte], properties: BasicProperties): Unit = {
    if (!verified) {
      import collection.convert.wrapAsJava._
      RabbitHelpers.tempChannel(c.getConnection) { _.queueDeclare(queue, true, false, false, Map.empty[String, AnyRef]) } match {
        case Left(ex) => throw ex
        case _ => ()
      }
      verified = true
    }
    c.basicPublish("", queue, properties, data)
  }
}