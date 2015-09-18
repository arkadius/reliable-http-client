import com.banno.license.Licenses._
import com.banno.license.Plugin.LicenseKeys._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import net.virtualvoid.sbt.graph.Plugin._
import sbt.Keys._
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._
import FilterKeys._

val commonSettings =
  filterSettings ++
  graphSettings ++
  licenseSettings ++
  Seq(
    organization  := "org.rhttpc",
    scalaVersion  := "2.11.7",
    scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
    license := apache2("Copyright 2015 the original author or authors."),
    licenses :=  Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://github.com/arkadius/reliable-http-client")),
    removeExistingHeaderBlock := true,
    dockerRepository := Some("arkadius"),
    resolvers ++= Seq(
      "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
    )
  )

val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  pomExtra in Global := {
    <scm>
      <connection>scm:git:github.com/arkadius/reliable-http-client.git</connection>
      <developerConnection>scm:git:git@github.com:arkadius/reliable-http-client.git</developerConnection>
      <url>github.com/arkadius/reliable-http-client</url>
    </scm>
    <developers>
      <developer>
        <id>ark_adius</id>
        <name>Arek Burdach</name>
        <url>https://github.com/arkadius</url>
      </developer>
    </developers>
  }
)

val akkaV = "2.4-M3"
val akkaStreamsV = "1.0"
val amqpcV = "3.5.4"
val json4sV = "3.2.11"
val logbackV = "1.1.3"
val slf4jV = "1.7.7"
val dispatchV = "0.11.3"
val scalaTestV = "3.0.0-M7"

lazy val transport = (project in file("rhttpc-transport")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-api",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-http-experimental"        % akkaStreamsV,
        "com.typesafe.akka"       %% "akka-actor"                    % akkaV,
        "com.typesafe.akka"       %% "akka-agent"                    % akkaV,
        "com.rabbitmq"             % "amqp-client"                   % amqpcV,
        "org.json4s"              %% "json4s-native"                 % json4sV,
        "org.scalatest"           %% "scalatest"                     % scalaTestV    % "test"
      )
    }
  )

lazy val client = (project in file("rhttpc-client")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-client",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-persistence"              % akkaV,
        "org.slf4j"                % "slf4j-api"                     % slf4jV,
        "com.typesafe.akka"       %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"           %% "scalatest"                     % scalaTestV    % "test",
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV         % "test",
        "ch.qos.logback"           % "logback-classic"               % logbackV      % "test"
      )
    }
  ).
  dependsOn(transport)

lazy val proxyApp = (project in file("rhttpc-proxy")).
  settings(commonSettings).
  enablePlugins(DockerPlugin).
  enablePlugins(JavaAppPackaging).
  settings(
    name := "rhttpc-proxy",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"           % "logback-classic"               % logbackV
      )
    },
    dockerExposedPorts := Seq(5005),
    dockerEntrypoint := Seq("bin/rhttpc-proxy", "-jvm-debug", "5005"),
    publishArtifact := false
  ).
  dependsOn(client)

lazy val sampleEcho = (project in file("sample/sample-echo")).
  settings(commonSettings).
  enablePlugins(DockerPlugin).
  enablePlugins(JavaAppPackaging).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-http-experimental"        % akkaStreamsV,
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"           %  "logback-classic"              % logbackV
      )
    },
    dockerExposedPorts := Seq(8082),
    publishArtifact := false
  )

lazy val sampleApp = (project in file("sample/sample-app")).
  settings(commonSettings).
  enablePlugins(DockerPlugin).
  enablePlugins(JavaAppPackaging).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-http-experimental"        % akkaStreamsV,
        "org.iq80.leveldb"          % "leveldb"                      % "0.7",
        "org.fusesource.leveldbjni" % "leveldbjni-all"               % "1.8",
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"           % "logback-classic"               % logbackV,
        "com.typesafe.akka"       %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"           %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    dockerExposedPorts := Seq(8081),
    publishArtifact := false
  ).
  dependsOn(client)

lazy val testProj = (project in file("sample/test")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.github.docker-java"   % "docker-java"                   % "1.4.0" exclude("commons-logging", "commons-logging"),
        "commons-io"               % "commons-io"                    % "2.4",
        "net.databinder.dispatch" %% "dispatch-core"                 % dispatchV,
        "ch.qos.logback"           %  "logback-classic"              % logbackV,
        "org.scalatest"           %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    Keys.test in Test <<= (Keys.test in Test).dependsOn(
      publishLocal in Docker in proxyApp,
      publishLocal in Docker in sampleEcho,
      publishLocal in Docker in sampleApp
    ),
    publishArtifact := false
  )

publishArtifact := false

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(action = Command.process("publishSigned", _)),
  setNextVersion,
  commitNextVersion,
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
  pushChanges
)
