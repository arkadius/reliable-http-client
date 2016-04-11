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
package rhttpc.transport.fallback

import akka.actor.{ActorSystem, Scheduler}
import akka.pattern.CircuitBreaker
import org.slf4j.LoggerFactory
import rhttpc.transport.{Message, Publisher}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

private[fallback] class FallbackPublisher[Msg](main: Publisher[Msg],
                                               fallback: Publisher[Msg])
                                              (maxFailures: Int,
                                               callTimeout: FiniteDuration,
                                               resetTimeout: FiniteDuration)
                                              (implicit system: ActorSystem) extends Publisher[Msg] {

  import system.dispatcher

  private val logger = LoggerFactory.getLogger(getClass)

  private val circuitBreaker = new CircuitBreaker(system.scheduler, maxFailures, callTimeout, resetTimeout)
    .onOpen(logger.debug("Circuit opened"))
    .onHalfOpen(logger.debug("Circuit half-opened"))
    .onClose(logger.debug("Circuit closed"))

  override def publish(msg: Message[Msg]): Future[Unit] = {
    circuitBreaker.withCircuitBreaker(main.publish(msg)).recoverWith {
      case NonFatal(ex) =>
        logger.debug(s"Circuit is opened, sending message [${msg.getClass.getName}] to fallback transport")
        fallback.publish(msg)
    }
  }

  override def start(): Unit = {
    main.start()
    fallback.start()
  }

  override def stop(): Future[Unit] = {
    import rhttpc.utils.Recovered._
    recoveredFuture("stopping main publisher", main.stop())
      .flatMap(_ => recoveredFuture("stopping fallback publisher", fallback.stop()))
  }
}