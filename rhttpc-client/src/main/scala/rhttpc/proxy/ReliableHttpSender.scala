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
package rhttpc.proxy

import akka.actor._
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern._
import akka.stream.Materializer
import akka.stream.scaladsl._
import rhttpc.transport.PubSubTransport
import rhttpc.transport.api.Correlated

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Success, Try}

abstract class ReliableHttpSender(implicit actorSystem: ActorSystem,
                                  materialize: Materializer,
                                  transport: PubSubTransport[Correlated[Try[HttpResponse]]]) {
  
  protected def batchSize: Int

  private val consumingActor = actorSystem.actorOf(Props(new Actor with ActorLogging {
    import context.dispatcher

    private val httpClient = Http().superPool[String]().mapAsync(batchSize) {
      case (tryResponse, id) =>
        tryResponse match {
          case Success(response) => response.toStrict(1 minute).map(strict => (Success(strict), id))
          case failure => Future.successful((failure, id))
        }
    }
    
    override def receive: Receive = {
      case correlated@Correlated(req: HttpRequest, correlationId) =>
        log.debug(s"Got $correlated")
        val originalSender = sender()
        // TODO: backpresure, move to flows
        Source.single((req, correlationId)).via(httpClient).runForeach {
          case (tryResponse, id) =>
            val correlated = Correlated(tryResponse, id)
            implicit val logImplicit = log
            handleResponse(correlated) pipeTo originalSender
        }
    }
  }))

  private val subscriber = transport.subscriber("rhttpc-request", consumingActor)
  
  protected def handleResponse(correlatedResponse: Correlated[Try[HttpResponse]])
                              (implicit ec: ExecutionContext, log: LoggingAdapter): Future[Unit]

  def run() {
    subscriber.run()
  }
  
  def close()(implicit ec: ExecutionContext): Future[Unit] = {
    subscriber.stop()
    gracefulStop(consumingActor, 30 seconds).map(stopped =>
      if (!stopped)
        throw new IllegalStateException("Consuming actor hasn't been stopped correctly")
    )
  }
  
}