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
package rhttpc

import org.slf4j.LoggerFactory
import rhttpc.client.subscription.{WithSubscriptionManager, ReplyFuture}
import rhttpc.transport.OutboundQueueData

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

package object client {
  type InOutReliableClient[Request] = ReliableClient[Request, ReplyFuture] with WithSubscriptionManager
  type InOnlyReliableClient[Request] = ReliableClient[Request, Future[Unit]]

  private lazy val logger = LoggerFactory.getLogger(getClass)

  def recovered(run: => Unit, action: String): Unit = {
    try {
      run
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Exception while $action", ex)

    }
  }

  def recoveredFuture(future: => Future[Unit], action: String)
                     (implicit ec: ExecutionContext): Future[Unit] = {
    try {
      future.recover {
        case NonFatal(ex) =>
          logger.error(s"Exception while $action", ex)
      }
    } catch {
      case NonFatal(ex) => // while preparing future
        logger.error(s"Exception while $action", ex)
        Future.successful(Unit)
    }
  }

  private[rhttpc] def prepareRequestPublisherQueueData(queuesPrefix: String) =
    OutboundQueueData(prepareRequestQueueName(queuesPrefix), delayed = true)

  private[rhttpc] def prepareRequestQueueName(queuesPrefix: String) = queuesPrefix + ".request"

  private[rhttpc] def prepareResponseQueueName(queuesPrefix: String) = queuesPrefix + ".response"

}