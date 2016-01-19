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

import akka.actor.ActorSystem
import com.rabbitmq.client.Connection
import slick.driver.JdbcDriver
import slick.jdbc.JdbcBackend

package object amqpjdbc {

  implicit def transport[PubMsg <: AnyRef, SubMsg](implicit actorSystem: ActorSystem,
                                                   connection: Connection,
                                                   driver: JdbcDriver,
                                                   db: JdbcBackend.Database,
                                                   serializer: Serializer[PubMsg],
                                                   deserializer: Deserializer[SubMsg],
                                                   msgSerializer: Serializer[Message[PubMsg]],
                                                   msgDeserializer: Deserializer[Message[PubMsg]]):
  PubSubTransport[PubMsg, SubMsg] with WithInstantPublisher with WithDelayedPublisher =
    AmqpJdbcTransport(
      connection = connection,
      driver = driver,
      db = db
    )

}