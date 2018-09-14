import com.banno.license.Licenses._
import com.banno.license.Plugin.LicenseKeys._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import sbt.Keys._
import sbt.dsl.enablePlugins
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._

val defaultScalaVersion = "2.12.1"
val scalaVersions = Seq("2.11.8", defaultScalaVersion)


val commonSettings =
  filterSettings ++
  licenseSettings ++
  Seq(
    organization  := "org.rhttpc",
    scalaVersion  := defaultScalaVersion,
    crossScalaVersions := scalaVersions,
    scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
    license := apache2("Copyright 2015 the original author or authors."),
    licenses :=  Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://github.com/arkadius/reliable-http-client")),
    removeExistingHeaderBlock := true,
    dockerRepository := Some("arkadius"),
    resolvers ++= Seq(
      "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
      Resolver.jcenterRepo
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

val akkaV = "2.4.17"
val akkaHttpV = "10.0.5"
val ficusV = "1.4.0"
val amqpcV = "3.6.5"
val json4sV = "3.4.2"
val argonaut62MinorV = "-RC2"
val logbackV = "1.1.7"
val commonsIoV = "2.5"
val slf4jV = "1.7.21"
val dispatchV = "0.12.0"
val scalaTestV = "3.0.1"
val slickV = "3.2.0"
val flywayV = "4.0.3"
val hsqldbV = "2.3.4"
val dockerJavaV = "1.4.0"

lazy val transport = (project in file("rhttpc-transport")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-transport",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-actor"                    % akkaV,
        "org.slf4j"                 % "slf4j-api"                     % slf4jV,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    }
  )

lazy val inMemTransport = (project in file("rhttpc-inmem")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-inmem",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test",
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV         % "test",
        "ch.qos.logback"            % "logback-classic"               % logbackV      % "test"
      )
    }
  ).
  dependsOn(transport)

lazy val amqpTransport = (project in file("rhttpc-amqp")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-amqp",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-agent"                    % akkaV,
        "com.rabbitmq"              % "amqp-client"                   % amqpcV,
        "com.iheart"               %% "ficus"                         % ficusV,
        "org.scala-lang"            % "scala-reflect"                 % defaultScalaVersion,
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test",

        "net.databinder.dispatch"  %% "dispatch-core"                 % dispatchV     % "test",
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV         % "test",
        "ch.qos.logback"            % "logback-classic"               % logbackV      % "test",
        "com.typesafe.akka"        %% "akka-http"                     % akkaHttpV     % "test"
      )
    }
  ).
  dependsOn(transport)

lazy val amqpJdbcTransport = (project in file("rhttpc-amqp-jdbc")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-amqp-jdbc",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.slick"       %% "slick"                         % slickV,
        "org.flywaydb"              % "flyway-core"                   % flywayV       % "optional",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test",
        "com.typesafe.slick"       %% "slick-hikaricp"                % slickV        % "test",
        "org.hsqldb"                % "hsqldb"                        % hsqldbV       % "test",
        "ch.qos.logback"            % "logback-classic"               % logbackV      % "test"
      )
    }
  ).
  dependsOn(amqpTransport)

lazy val json4sSerialization = (project in file("rhttpc-json4s")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-json4s",
    libraryDependencies ++= {
      Seq(
        "org.json4s"               %% "json4s-native"                 % json4sV,
        "org.scala-lang"            % "scala-reflect"                 % defaultScalaVersion,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    }
  ).
  dependsOn(transport)

lazy val argonaut62Serialization = (project in file("rhttpc-argonaut_6.2")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-argonaut_6.2",
    libraryDependencies ++= {
      Seq(
        "io.argonaut"              %% "argonaut"                      % s"6.2$argonaut62MinorV"
      )
    }
  ).
  dependsOn(transport)

lazy val client = (project in file("rhttpc-client")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-client",
    libraryDependencies ++= {
      Seq(
        "com.iheart"               %% "ficus"                         % ficusV,
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test",
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV         % "test",
        "ch.qos.logback"            % "logback-classic"               % logbackV      % "test"
      )
    }
  ).
  dependsOn(transport).
  dependsOn(inMemTransport % "test")

lazy val akkaHttpClient = (project in file("rhttpc-akka-http")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-akka-http",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-http"                     % akkaHttpV,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    }
  ).
  dependsOn(client).
  dependsOn(amqpTransport).
  dependsOn(json4sSerialization).
  dependsOn(inMemTransport)

lazy val akkaPersistence = (project in file("rhttpc-akka-persistence")).
  settings(commonSettings).
  settings(publishSettings).
  settings(
    name := "rhttpc-akka-persistence",
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-persistence"              % akkaV
      )
    }
  ).
  dependsOn(client % "compile->compile;test->test").
  dependsOn(json4sSerialization)

lazy val sampleEcho = (project in file("sample/sample-echo")).
  settings(commonSettings).
  enablePlugins(DockerPlugin).
  enablePlugins(JavaAppPackaging).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-http"                     % akkaHttpV,
        "com.typesafe.akka"        %% "akka-agent"                    % akkaV,
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
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
        "com.typesafe.akka"        %% "akka-http"                     % akkaHttpV,
        "org.iq80.leveldb"          % "leveldb"                       % "0.7",
        "org.fusesource.leveldbjni" % "leveldbjni-all"                % "1.8",
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    dockerExposedPorts := Seq(8081),
    publishArtifact := false
  ).
  dependsOn(akkaHttpClient).
  dependsOn(akkaPersistence)

lazy val testProj = (project in file("sample/test")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.github.docker-java"    % "docker-java"                   % dockerJavaV exclude("commons-logging", "commons-logging"),
        "commons-io"                % "commons-io"                    % commonsIoV,
        "net.databinder.dispatch"  %% "dispatch-core"                 % dispatchV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    Keys.test in Test <<= (Keys.test in Test).dependsOn(
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

enablePlugins(CrossPerProjectPlugin)