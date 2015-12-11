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

import akka.actor.ActorRef

import scala.concurrent.Future
import scala.language.higherKinds
import scala.util.Try

trait PubSubTransport[PubMsg, SubMsg, In, Out, Props] {
  def publisher(queueData: Out): Publisher[PubMsg, Props]

  def subscriber(queueData: In, consumer: ActorRef): Subscriber[SubMsg]
}

trait Publisher[Msg, -Props] {

  def publish(msg: Msg, properties: Option[Props] = None): Future[Unit]

  def close(): Unit

}

trait Serializer[PubMsg] {
  def serialize(obj: PubMsg): String
}

trait Subscriber[SubMsg] {

  def run(): Unit

  def stop(): Unit
}

trait Deserializer[SubMsg] {
  def deserialize(value: String): Try[SubMsg]
}