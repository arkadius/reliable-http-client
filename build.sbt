import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import sbt.Keys._
import ReleaseTransformations._

val defaultScalaVersion = "2.13.6"
val scalaVersions = Seq("2.12.13", defaultScalaVersion)

val commonSettings =
  Seq(
    organization  := "org.rhttpc",
    scalaVersion  := defaultScalaVersion,
    crossScalaVersions := scalaVersions,
    scalacOptions := Seq(
      "-unchecked",
      "-deprecation",
      "-encoding", "utf8",
      "-feature",
      "-Xfatal-warnings",
      "-language:postfixOps"),
    headerLicense := Some(HeaderLicense.Custom(
      """|Copyright 2015 the original author or authors.
         |
         |Licensed under the Apache License, Version 2.0 (the "License");
         |you may not use this file except in compliance with the License.
         |You may obtain a copy of the License at
         |
         |    http://www.apache.org/licenses/LICENSE-2.0
         |
         |Unless required by applicable law or agreed to in writing, software
         |distributed under the License is distributed on an "AS IS" BASIS,
         |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         |See the License for the specific language governing permissions and
         |limitations under the License.
         |""".stripMargin
    )),
    headerEmptyLine := false,
    homepage := Some(url("https://github.com/arkadius/reliable-http-client")),
    dockerRepository := Some("arkadius"),
    dockerBaseImage := "openjdk:8",
    resolvers ++= Seq(
      "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
      Resolver.jcenterRepo
    ),
    ThisBuild / libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.5" cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % "1.7.5" % Provided cross CrossVersion.full
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
  Test / publishArtifact := false,
  Global / pomExtra := {
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

val akkaV             = "2.5.32"
val akkaHttpV         = "10.1.13"
val amqpcV            = "3.6.6"
val argonaut62MinorV  = ".3"
val betterFilesV      = "3.9.1"
val commonsIoV        = "2.5"
val dispatchV         = "1.2.0"
val ficusV            = "1.4.7"
val flywayV           = "6.2.4"
val hsqldbV           = "2.3.6"
val json4sV           = "3.6.11"
val jaxbV             = "2.3.1"
val logbackV          = "1.1.11"
val scalaCompatV      = "2.4.4"
val scalaTestV        = "3.2.9"
val slf4jV            = "1.7.26"
val slickV            = "3.3.2"
val testContainersV   = "0.39.5"

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
        "com.typesafe.akka"        %% "akka-stream"                   % akkaV,
        "com.rabbitmq"              % "amqp-client"                   % amqpcV,
        "com.iheart"               %% "ficus"                         % ficusV,
        "org.scala-lang.modules"   %% "scala-collection-compat"       % scalaCompatV,
        "org.scala-lang"            % "scala-reflect"                 % scalaVersion.value,
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test",

        "org.dispatchhttp"         %% "dispatch-core"                 % dispatchV     % "test",
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
        "org.scala-lang"            % "scala-reflect"                 % scalaVersion.value,
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
        "org.scala-lang.modules"   %% "scala-collection-compat"       % scalaCompatV,
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
        "com.typesafe.akka"        %% "akka-stream"                   % akkaV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    dockerExposedPorts := Seq(8082),
    publish / skip := true
  )

lazy val sampleApp = (project in file("sample/sample-app")).
  settings(commonSettings).
  settings(Seq(
    bashScriptExtraDefines += s"""addJava "-Dlogback.configurationFile=/etc/${name.value}/logback.xml""""
  )).
  enablePlugins(DockerPlugin).
  enablePlugins(JavaAppPackaging).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"        %% "akka-http"                     % akkaHttpV,
        "org.iq80.leveldb"          % "leveldb"                       % "0.12",
        "org.fusesource.leveldbjni" % "leveldbjni-all"                % "1.8",
        "com.typesafe.akka"        %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "com.typesafe.akka"        %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    dockerExposedPorts := Seq(8081),
    publish / skip := true
  ).
  dependsOn(akkaHttpClient).
  dependsOn(akkaPersistence)

lazy val testProj = (project in file("sample/test")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.github.pathikrit"     %% "better-files"                  % betterFilesV,
        "commons-io"                % "commons-io"                    % commonsIoV,
        "org.dispatchhttp"         %% "dispatch-core"                 % dispatchV,
        "javax.xml.bind"            % "jaxb-api"                      % jaxbV,
        "ch.qos.logback"            % "logback-classic"               % logbackV,
        "com.dimafeng"             %% "testcontainers-scala-scalatest" % testContainersV % "test",
        "com.dimafeng"             %% "testcontainers-scala-rabbitmq" % testContainersV % "test",
        "org.scalatest"            %% "scalatest"                     % scalaTestV    % "test"
      )
    },
    Test / Keys.test  := (Test / Keys.test).dependsOn(
      sampleEcho / Docker / publishLocal,
      sampleApp / Docker / publishLocal
    ).value,
    publish / skip := true
  )

lazy val root = (project in file("."))
  .aggregate(
    transport, inMemTransport, amqpTransport, amqpJdbcTransport,
    json4sSerialization, argonaut62Serialization,
    client, akkaHttpClient,
    akkaPersistence,
    sampleEcho, sampleApp, testProj)
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    // crossScalaVersions must be set to Nil on the aggregating project
    releaseCrossBuild := true,
    publish / skip := true,
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      ReleaseStep { st: State =>
        if (!st.get(ReleaseKeys.skipTests).getOrElse(false)) {
          releaseStepCommandAndRemaining("+test")(st)
        } else {
          st
        }
      },
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("+publishSigned"),
      setNextVersion,
      commitNextVersion,
      ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
      pushChanges
    )
  )
