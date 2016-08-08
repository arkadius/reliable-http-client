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
package rhttpc.transport.amqpjdbc

import rhttpc.transport.{DelayedMessage, Message, Publisher}

import scala.concurrent.{ExecutionContext, Future}
import rhttpc.utils.Recovered._

private[amqpjdbc] class AmqpJdbcPublisher[PubMsg](underlying: Publisher[PubMsg],
                                                  queueName: String,
                                                  scheduler: AmqpJdbcScheduler[PubMsg],
                                                  additionalStopAction: => Future[Unit])
                                                 (implicit ec: ExecutionContext) extends Publisher[PubMsg] {

  override def publish(msg: Message[PubMsg]): Future[Unit] = {
    msg match {
      case delayed@DelayedMessage(content, delay, attempt) =>
        scheduler.schedule(delayed, delay)
      case otherMessage =>
        underlying.publish(msg)
    }
  }


  override def start(): Unit = {
    underlying.start()
    scheduler.start()
  }

  override def stop(): Future[Unit] = {
    recoveredFuture("stopping scheduler", scheduler.stop())
      .flatMap(_ => recoveredFuture("stopping underlying publisher", underlying.stop()))
      .flatMap(_ => recoveredFuture("additional action", additionalStopAction))
  }

}