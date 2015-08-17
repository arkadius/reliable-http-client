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
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.pattern._
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object EchoApp extends App with Directives {

  implicit val system = ActorSystem("rhttpc-echo")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val route = (post & entity(as[String])) { msg =>
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