import net.virtualvoid.sbt.graph.Plugin._
import com.banno.license.Plugin.LicenseKeys._
import com.banno.license.Licenses._
import ReleaseTransformations._

val commonSettings =
  seq(graphSettings : _*) ++
  seq(licenseSettings : _*) ++
  Seq(
    organization  := "org.github",
    scalaVersion  := "2.11.7",
    scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
    license := apache2("Copyright 2015 the original author or authors."),
    removeExistingHeaderBlock := true
  ) 

val akkaV = "2.3.12"
val akkaStreamsV = "1.0"
val json4sV = "3.2.11"
val logbackV = "1.1.3"
val dispatchV = "0.11.3"
val scalaTestV = "2.2.5"

lazy val client = (project in file("client")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-persistence-experimental" % akkaV,
        "org.json4s"              %% "json4s-native"                 % json4sV
      )
    }
  )

lazy val server = (project in file("server")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
      )
    }
  )

lazy val sampleEcho = (project in file("sample-echo")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-http-experimental"        % akkaStreamsV,
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"           %  "logback-classic"              % logbackV
      )
    }
  )

lazy val sampleApp = (project in file("sample-app")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka"       %% "akka-http-experimental"        % akkaStreamsV,
        "net.databinder.dispatch" %% "dispatch-core"                 % dispatchV,
        "com.typesafe.akka"       %% "akka-slf4j"                    % akkaV,
        "ch.qos.logback"           % "logback-classic"               % logbackV,
        "com.typesafe.akka"       %% "akka-testkit"                  % akkaV         % "test",
        "org.scalatest"           %% "scalatest"                     % scalaTestV    % "test"
      )
    }
  ).
  dependsOn(client)

lazy val test = (project in file("test")).
  settings(commonSettings).
  settings(
    libraryDependencies ++= {
      Seq(
        "net.databinder.dispatch" %% "dispatch-core"                 % dispatchV
      )
    }
  ).
  dependsOn(sampleApp, sampleEcho)

resolvers ++= Seq(
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository"
)

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,              // : ReleaseStep
  inquireVersions,                        // : ReleaseStep
  runTest,                                // : ReleaseStep
  setReleaseVersion,                      // : ReleaseStep
  commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
  tagRelease,                             // : ReleaseStep
  setNextVersion,                         // : ReleaseStep
  commitNextVersion,                      // : ReleaseStep
  pushChanges                             // : ReleaseStep, also checks that an upstream branch is properly configured
)
