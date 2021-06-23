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
package rhttpc.echo

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.pattern._
import akka.stream.ActorMaterializer
import com.github.ghik.silencer.silent

import scala.concurrent.Future
import scala.concurrent.duration._

object EchoApp extends App with Directives {

  implicit val system = ActorSystem("rhttpc-echo")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  @silent val retryAgent = Agent(Map.empty[String, Int])

  val route = (post & entity(as[String])) {
    case request@FailNTimesThanReplyWithMessage(failsCount, eventualMessage) =>
      complete {
        retryAgent.alter { currectRetryMap =>
          val current = currectRetryMap.getOrElse(request, 0)
          val next = current + 1
          if (next > failsCount) {
            currectRetryMap - request
          } else {
            currectRetryMap + (request -> next)
          }
        }.flatMap { retryMapAfterChange =>
          retryMapAfterChange.get(request) match {
            case Some(retry) => Future.failed(new Exception(s"Failed $retry time"))
            case None => Future.successful(eventualMessage)
          }
        }
      }
    case msg =>
      complete {
        system.log.debug(s"Got: $msg")
        after(5 seconds, system.scheduler) {
          system.log.debug(s"Reply with: $msg")
          Future.successful(msg)
        }
      }
  }

  Http().bindAndHandle(route, interface = "0.0.0.0", port = 8082)
}




object FailNTimesThanReplyWithMessage {
  private val Regex = "fail-(\\d*)-times-than-reply-with-(.*)".r("failsCount", "eventualMessage")

  def unapply(str: String): Option[(Int, String)] = str match {
    case Regex(failsCount, eventualMessage) => Some(failsCount.toInt, eventualMessage)
    case other => None
  }
}