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
package rhttpc.client

import java.util.UUID

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.HttpRequest
import com.spingo.op_rabbit.QueueMessage
import rhttpc.api.Correlated
import rhttpc.api.json4s.Json4sSerializer

class ReliableHttp(implicit actorFactory: ActorRefFactory, rabbitControlActor: RabbitControlActor) {
  import Json4sSerializer.formats
  import com.spingo.op_rabbit.Json4sSupport._

  def send(request: HttpRequest): PipeableExecutable = {
    val correlationId = UUID.randomUUID().toString
    def sendRequest() = rabbitControlActor.rabbitControl ! QueueMessage(Correlated(request, correlationId), "rhttpc-request")
    new PipeableExecutable(SubscriptionOnResponse(correlationId))(sendRequest())
  }
}

object ReliableHttp {
  def apply()(implicit actorFactory: ActorRefFactory, rabbitControlActor: RabbitControlActor) = new ReliableHttp()
}