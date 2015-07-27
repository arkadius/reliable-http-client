package reliablehttpc.test

import com.kolor.docker.api.DockerClient
import com.kolor.docker.api.entities.ContainerConfiguration
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object DockerEnrichments {
  implicit class DockerEnrichment(docker: DockerClient) {
    implicit val dockerClientImplicit = docker
    lazy val logger = LoggerFactory.getLogger(getClass)

    def containerFindByNameOrCreate(containerName: String, image: String, containerConfig: ContainerConfiguration) = {
      for {
        containers <- docker.containers()
        containerId <- {
          containers.find(_.names.exists(_.contains(containerName))) match {
            case Some(container) =>
              logger.debug(s"Found container for name: $containerName: $container")
              Future.successful(container.id)
            case None =>
              logger.info(s"Not found container for name: $containerName. Creating for image: $image with config: $containerConfig")
              docker.containerCreate(image, containerConfig, Some(containerName)).map {
                case (createdContainerId, warnings) =>
                  logger.warn(warnings.mkString(s"Warnings during container create with name: $containerName for image: $image with config: $containerConfig:\n", ",\n", ""))
                  createdContainerId
              }
          }
        }
      } yield containerId
    }
  }
}
