publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomExtra in Global := {
  <url>https://github.com/arkadius/reliable-http-client</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
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
