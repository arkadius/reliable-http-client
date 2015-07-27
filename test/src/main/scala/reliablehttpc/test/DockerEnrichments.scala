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
package reliablehttpc.test

import org.slf4j.LoggerFactory
import tugboat._

import scala.concurrent.{ExecutionContext, Future}

object DockerEnrichments {
  lazy val logger = LoggerFactory.getLogger(getClass)

  implicit class DockerEnrichment(docker: Docker) {
    def containerFindByNameOrCreate(containerName: String, image: String)
                                   (implicit ec: ExecutionContext): Future[String] = {
      for {
        containers: List[Container] <- docker.containers.list.all(true)()
        containerId <- {
          containers.find(_.names.exists(_.contains(containerName))) match {
            case Some(container) =>
              logger.debug(s"Found container for name: $containerName: $container")
              Future.successful(container.id)
            case None =>
              logger.info(s"Not found container for name: $containerName. Creating for image: $image")
              val createFuture: Future[Create.Response] = docker.containers.create(image)()
              createFuture.map {
                case Create.Response(createdContainerId, warnings) =>
                  logger.warn(warnings.mkString(s"Warnings during container create with name: $containerName for image: $image:\n", ",\n", ""))
                  createdContainerId
              }
          }
        }
      } yield containerId
    }
  }
}