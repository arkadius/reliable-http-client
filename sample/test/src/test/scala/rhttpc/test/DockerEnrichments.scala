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

import java.io._
import java.nio.ByteBuffer

import com.github.dockerjava.api._
import com.github.dockerjava.api.command.CreateContainerCmd
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.StringEscapeUtils
import org.slf4j.LoggerFactory

import scala.collection.convert.wrapAsScala._

object DockerEnrichments {
  lazy val logger = LoggerFactory.getLogger(getClass)

  implicit class DockerEnrichment(docker: DockerClient) {
    def containerStartFromScratch(containerName: String, repo: String, tag: String)
                                 (prepareCreateCommand: CreateContainerCmd => CreateContainerCmd): String = {
      val image = s"$repo:$tag"
      val images = docker.listImagesCmd().exec().toList
      if (!images.exists(_.getRepoTags.contains(image))) {
        logger.info(s"Not found image: $image. Pulling ...")
        UnescapingWriter.copyToSysOut(docker.pullImageCmd(repo).withTag(tag).exec())
      }
      val containerId = containerCleanCreateByName(containerName, image)(prepareCreateCommand)
      docker.startContainerCmd(containerId).exec()
      attachLogging(containerId)
      containerId
    }

    private def containerCleanCreateByName(containerName: String, image: String)
                                          (prepareCreateCommand: CreateContainerCmd => CreateContainerCmd): String = {
      filterContainerByName(containerName).foreach { container =>
        logger.warn(s"Found container for name: $containerName: $container. Removing ...")
        docker.removeContainerCmd(container.getId).withForce().exec()
        filterContainerByName(containerName).foreach { container =>
          throw new IllegalStateException(s"Container still exists after remove: $container")
        }
      }
      val createWithNameCommand = docker.createContainerCmd(image).withName(containerName)
      val createResult = prepareCreateCommand(createWithNameCommand).exec()
      if (Option(createResult.getWarnings).exists(_.nonEmpty)) {
        logger.warn(createResult.getWarnings.mkString(s"Warnings during container create with name: $containerName for image: $image:\n", ",\n", ""))
      }
      createResult.getId
    }

    private def filterContainerByName(containerName: String) = {
      docker.listContainersCmd.withShowAll(true).exec().filter(_.getNames.exists(_.contains(containerName)))
    }

    def attachLogging(containerId: String) = {
      val loggingThread = new Thread {
        override def run(): Unit = {
          val input = docker.logContainerCmd(containerId).withStdOut().withStdErr().withFollowStream().withTail(0).exec()
          DockerLogStreamReader.copyToSysOut(input)
        }
      }
      loggingThread.setDaemon(true)
      loggingThread.start()
    }

    def stopAndRemoveContainer(containerId: String) = {
      logger.info(s"Graceful stopping container: $containerId ...")
      docker.stopContainerCmd(containerId).exec()
      logger.info(s"Container shutdown successful: $containerId. Removing ...")
      docker.removeContainerCmd(containerId).withForce().exec()
      logger.info(s"Container remove successful: $containerId")
    }
  }
}

class UnescapingWriter(out: Writer) extends Writer {
  override def flush(): Unit = out.flush()

  override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
    StringEscapeUtils.unescapeJava(out, new String(cbuf, off, len) + "\n")
  }

  override def close(): Unit = out.close()
}

object UnescapingWriter {
  def copyToSysOut(from: InputStream) = {
    IOUtils.copy(new BufferedReader(new InputStreamReader(from)), new UnescapingWriter(new OutputStreamWriter(System.out)))
  }
}

object DockerLogStreamReader {
  def copyToSysOut(from: InputStream) = {
    readDockerRawStream(from, System.out)
  }

  private def readDockerRawStream(rawStream: InputStream, out: OutputStream) {
    val header = Array.ofDim[Byte](8)
    while (rawStream.read(header) > 0) {
      val headerBuffer = ByteBuffer.wrap(header)
      headerBuffer.get
      headerBuffer.get
      headerBuffer.get
      headerBuffer.get
      val size = headerBuffer.getInt
      val streamOutputBuffer = Array.ofDim[Byte](size)
      rawStream.read(streamOutputBuffer)
      out.write(streamOutputBuffer)
    }
  }
}