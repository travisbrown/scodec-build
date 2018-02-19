package scodec.build

import sbt._
import Keys._
import sbtrelease._
import ReleasePlugin.autoImport._
import ReleaseStateTransformations._
import Utilities._
import com.typesafe.sbt.osgi.SbtOsgi
import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.SbtGit._
import com.typesafe.sbt.sbtghpages.GhpagesPlugin
import com.typesafe.sbt.sbtghpages.GhpagesPlugin.autoImport._
import GitKeys._
import com.typesafe.sbt.git.GitRunner
import com.typesafe.sbt.SbtPgp
import SbtPgp.autoImport._
import com.typesafe.sbt.site._
import com.typesafe.sbt.site.SitePlugin.autoImport._
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys._
import pl.project13.scala.sbt.SbtJmh
import SbtJmh.autoImport._
import sbtbuildinfo.BuildInfoPlugin
import BuildInfoPlugin.autoImport._

object ScodecBuildSettings extends AutoPlugin {

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport {

    lazy val scodecModule = settingKey[String]("Name of the scodec module (should match github repository name)")

    lazy val githubProject = settingKey[String]("Name of the github project for this repository - defaults to scodecModule.value")

    lazy val githubHttpUrl = settingKey[String]("HTTP URL to the github repository")

    case class Contributor(githubUsername: String, name: String)
    lazy val contributors = settingKey[Seq[Contributor]]("Contributors to the project")

    lazy val rootPackage = settingKey[String]("Root package of the project")

    lazy val docSourcePath = settingKey[File]("Path to pass as -sourcepath argument to ScalaDoc")

    def commonJsSettings: Seq[Setting[_]] = Seq(
      scalacOptions in Compile += {
        val dir = project.base.toURI.toString.replaceFirst("[^/]+/?$", "")
        val url = s"https://raw.githubusercontent.com/scodec/${scodecModule.value}"
        val tagOrBranch = {
          if (version.value endsWith "SNAPSHOT") gitCurrentBranch.value
          else ("v" + version.value)
        }
        s"-P:scalajs:mapSourceURI:$dir->$url/$tagOrBranch/"
      }
  )
  }
  import autoImport._

  private def keySettings = Seq(
    githubProject := scodecModule.value,
    githubHttpUrl := s"https://github.com/scodec/${githubProject.value}/",
    contributors := Seq.empty
  )

  private def ivySettings = Seq(
    organization := "org.scodec",
    organizationHomepage := Some(new URL("http://scodec.org")),
    licenses += ("Three-clause BSD-style", url(githubHttpUrl.value + "blob/master/LICENSE")),
    unmanagedResources in Compile ++= {
      val base = baseDirectory.value
      (base / "NOTICE") +: (base / "LICENSE") +: ((base / "licenses") * "LICENSE_*").get
    },
    git.remoteRepo := "git@github.com:scodec/${githubProject.value}.git"
  )

  private def scalaSettings = Seq(
    scalaVersion := "2.11.12",
    crossScalaVersions := Seq("2.11.12", "2.12.4", "2.13.0-M3"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-unchecked",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-value-discard",
      "-Xfuture"
    ) ++ ifAtLeast(scalaBinaryVersion.value, "2.11.0")(
      "-Ywarn-unused-import"
    ),
    docSourcePath := baseDirectory.value,
    scalacOptions in (Compile, doc) := {
      val tagOrBranch = {
        if (version.value endsWith "SNAPSHOT") gitCurrentBranch.value
        else ("v" + version.value)
      }
      Seq(
        "-diagrams",
        "-groups",
        "-implicits",
        "-implicits-show-all",
        "-sourcepath", docSourcePath.value.getCanonicalPath,
        "-doc-source-url", githubHttpUrl.value + "tree/" + tagOrBranch + "€{FILE_PATH}.scala"
      )
    },
    scalacOptions in (Compile, console) ~= { _ filterNot { o => o == "-Ywarn-unused-import" || o == "-Xfatal-warnings" } },
    scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oD")
  )

  private def ifAtLeast(scalaBinaryVersion: String, atLeastVersion: String)(options: String*): Seq[String] = {
    case class ScalaBinaryVersion(major: Int, minor: Int) extends Ordered[ScalaBinaryVersion] {
      def compare(that: ScalaBinaryVersion) = Ordering[(Int, Int)].compare((this.major, this.minor), (that.major, that.minor))
    }
    val Pattern = """(\d+)\.(\d+).*""".r
    def getScalaBinaryVersion(v: String) = v match { case Pattern(major, minor) => ScalaBinaryVersion(major.toInt, minor.toInt) }
    if (getScalaBinaryVersion(scalaBinaryVersion) >= getScalaBinaryVersion(atLeastVersion)) options
    else Seq.empty
  }

  private def publishingSettings = Seq(
    resolvers += "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/",
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { x => false },
    pomExtra := (
      <url>http://github.com/scodec/{githubProject.value}</url>
      <scm>
        <url>git@github.com:scodec/{githubProject.value}.git</url>
        <connection>scm:git:git@github.com:scodec/{githubProject.value}.git</connection>
      </scm>
      <developers>
        {for (Contributor(username, name) <- contributors.value) yield
        <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
        }
      </developers>
    ),
    pomPostProcess := { (node) =>
      import scala.xml._
      import scala.xml.transform._
      def stripIf(f: Node => Boolean) = new RewriteRule {
        override def transform(n: Node) =
          if (f(n)) NodeSeq.Empty else n
      }
      val stripTestScope = stripIf { n => n.label == "dependency" && (n \ "scope").text == "test" }
      new RuleTransformer(stripTestScope).transform(node)(0)
    },
    useGpg := true,
    useGpgAgent := true
  )

  private def releaseSettings = {
    val publishSite = (ref: ProjectRef) => ReleaseStep(
      check = releaseStepTaskAggregated(makeSite in ref),
      action = releaseStepTaskAggregated(ghpagesPushSite in ref)
    )
    Seq(
      releaseCrossBuild := true,
      releasePublishArtifactsAction := PgpKeys.publishSigned.value,
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runTest,
        setReleaseVersion,
        commitReleaseVersion,
        tagRelease,
        publishArtifacts,
        publishSite(thisProjectRef.value),
        setNextVersion,
        commitNextVersion,
        pushChanges
      )
    )
  }

  override def projectSettings = keySettings ++ ivySettings ++ scalaSettings ++ publishingSettings ++ releaseSettings

}

object ScodecPrimaryModuleSettings extends AutoPlugin {

  override def requires = ScodecBuildSettings
  override def trigger = noTrigger

  import ScodecBuildSettings.autoImport._

  override def projectSettings = Seq(
    name := scodecModule.value,
    autoAPIMappings := true,
    apiURL := Some(url(s"http://scodec.org/api/${scodecModule.value}/${version.value}/")),
    buildInfoPackage := rootPackage.value,
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit)
  )

}

object ScodecPrimaryModuleJVMSettings extends AutoPlugin {

  override def requires = ScodecPrimaryModuleSettings && SiteScaladocPlugin && GhpagesPlugin
  override def trigger = noTrigger

  import ScodecBuildSettings.autoImport._

  // From https://github.com/sbt/website/blob/4ff41b9ad8b9a3613e559429555689090cb9fa29/project/Docs.scala
  private def gitRemoveFiles(dir: File, files: List[File], git: GitRunner, s: TaskStreams): Unit = {
    if(!files.isEmpty)
      git(("rm" :: "-r" :: "-f" :: "--ignore-unmatch" :: files.map(_.getAbsolutePath)) :_*)(dir, s.log)
    ()
  }

  private def siteSettings = Seq(
    git.remoteRepo := "git@github.com:scodec/scodec.github.io.git",
    GitKeys.gitBranch in ghpagesUpdatedRepository := Some("master"),
    siteMappings ++= {
      val m = (mappings in packageDoc in Compile).value
      for((f, d) <- m) yield (f, d)
    },
    ghpagesSynchLocal := {
      // Adapted from https://github.com/sbt/website/blob/4ff41b9ad8b9a3613e559429555689090cb9fa29/project/Docs.scala
      val repo = ghpagesUpdatedRepository.value
      val nonversioned = repo / "api" / scodecModule.value
      val versioned = nonversioned / version.value
      val git = GitKeys.gitRunner.value
      val s = streams.value

      gitRemoveFiles(repo, IO.listFiles(versioned).toList, git, streams.value)
      if (!version.value.endsWith("-SNAPSHOT")) {
        val snapshotVersion = version.value + "-SNAPSHOT"
        val snapshotVersioned = nonversioned / snapshotVersion
        if (snapshotVersioned.exists)
          gitRemoveFiles(repo, IO.listFiles(snapshotVersioned).toList, git, s)
      }

      val mappings =  for {
        (file, target) <- siteMappings.value
      } yield (file, versioned / target)
      IO.copy(mappings)

      repo
    }
  )

  private def osgiSettings = SbtOsgi.projectSettings ++ Seq(
    OsgiKeys.exportPackage := Seq(rootPackage.value + ".*;version=${Bundle-Version}"),
    OsgiKeys.importPackage := Seq(
      """scodec.*;version="$<range;[==,=+);$<@>>"""",
      """scala.*;version="$<range;[==,=+);$<@>>"""",
      """shapeless.*;version="$<range;[==,=+);$<@>>"""",
      "*"
    ),
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  )

  private def mimaSettings = mimaDefaultSettings ++ Seq(
    mimaPreviousArtifacts := previousVersion(version.value).map { pv =>
      organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
    }.toSet
  )

  private def previousVersion(currentVersion: String): Option[String] = {
    val Version = """(\d+)\.(\d+)\.(\d+).*""".r
    val Version(x, y, z) = currentVersion
    if (z == "0") None
    else Some(s"$x.$y.${z.toInt - 1}")
  }

  override def projectSettings = siteSettings ++ osgiSettings ++ mimaSettings

}
