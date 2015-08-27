resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
  url("http://dl.bintray.com/banno/oss"))(
    Resolver.ivyStylePatterns)

resolvers += Resolver.url(
  "sbt-plugin-releases",
  new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/")
)(Resolver.ivyStylePatterns)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.5")

addSbtPlugin("com.banno" % "sbt-license-plugin" % "0.1.4")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.3")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.5.1")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

addSbtPlugin("com.github.sdb" % "xsbt-filter" % "0.4")