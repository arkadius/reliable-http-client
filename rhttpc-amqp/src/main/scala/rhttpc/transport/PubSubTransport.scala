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

trait PubSubTransport[PubMsg] {
  def publisher(queueName: String): Publisher[PubMsg]

  def subscriber(queueName: String, consumer: ActorRef): Subscriber
}

trait PubSubTransportFactory {
  type DataT[P, S] <: TransportCreateData[P, S]

  def create[PubMsg <: AnyRef, SubMsg <: AnyRef](data: DataT[PubMsg, SubMsg]): PubSubTransport[PubMsg]
}

trait TransportCreateData[PubMsg, SubMsg]

trait Publisher[Msg] {
  def publish(msg: Msg): Future[Unit]

  def close(): Unit
}

trait Subscriber {
  def run(): Unit
  
  def stop(): Unit
}