resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
  url("http://dl.bintray.com/banno/oss"))(
    Resolver.ivyStylePatterns)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

addSbtPlugin("com.banno" % "sbt-license-plugin" % "0.1.5")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.10")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.1.6")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.2")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.1")

addSbtPlugin("com.github.sdb" % "xsbt-filter" % "0.4")

addSbtPlugin("com.eed3si9n" % "sbt-doge" % "0.1.5")
