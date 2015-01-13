import sbtrelease._
import ReleaseStateTransformations._
import ReleasePlugin._
import ReleaseKeys._

organization := "org.scodec"
name := "scodec-build"

sbtPlugin := true

resolvers += "jgit-repo" at "http://download.eclipse.org/jgit/maven"

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.7.0")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.5")
addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.8.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.3")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.6")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.1.6")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.3.2")

licenses += ("Three-clause BSD-style", url("https://github.com/scodec/scodec-build/blob/master/LICENSE"))

publishTo <<= version { v: String =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { x => false }
pomExtra := (
  <url>http://github.com/scodec/scodec-build</url>
  <scm>
    <url>git@github.com:scodec/scodec-build.git</url>
    <connection>scm:git:git@github.com:scodec/scodec-build.git</connection>
  </scm>
  <developers>
    <developer>
      <id>mpilquist</id>
      <name>Michael Pilquist</name>
      <url>http://github.com/mpilquist</url>
    </developer>
  </developers>
)

releaseSettings
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts.copy(action = publishSignedAction),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
