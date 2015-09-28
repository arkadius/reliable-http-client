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
package rhttpc.test

import dispatch._
import dispatch.Defaults.timer

import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

case class HttpProbe(urlStr: String) {
  def await(atMostSeconds: Int = 15)(implicit ec: ExecutionContext) = {
    val http = Http().configure(_.setConnectTimeout(500).setRequestTimeout(500))
    val future = retry.Pause(max = atMostSeconds * 2) { () => // default delay is 0,5 sec
      http(url(urlStr) OK as.String).map(Some(_)).recover {
        case NonFatal(ex) => None
      }
    }
    Await.result(future, atMostSeconds seconds)
  }
}