import ReleaseTransformations._

// Constants //

val projectName = "http4s-active-requests"
val scala211    = "2.11.12"
val scala212    = "2.12.8"

// Lazy

lazy val scalaVersions = List(scala211, scala212)

// Groups //

val fs2G       = "co.fs2"
val http4sG    = "org.http4s"
val scalatestG = "org.scalatest"

// Artifacts //

val fs2CoreA      = "fs2-core"
val http4sServerA = "http4s-server"
val scalatestA    = "scalatest"

// Versions //

val fs2V       = "1.0.5"
val http4sV    = "0.20.7"
val scalatestV = "3.0.7"

// GAVs //

lazy val fs2Core      = fs2G       %% fs2CoreA      % fs2V
lazy val http4sServer = http4sG    %% http4sServerA % http4sV
lazy val scalatest    = scalatestG %% scalatestA    % scalatestV

// ThisBuild Scoped Settings //

ThisBuild / organization := "io.isomarcte"
ThisBuild / scalaVersion := scala212
ThisBuild / scalacOptions += "-target:jvm-1.8"
ThisBuild / javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

// General Configuration //
lazy val publishSettings = Seq(
  homepage := Some(
    url("https://github.com/isomarcte/http4s-active-requests")
  ),
  licenses := Seq(
    "BSD3" -> url("https://opensource.org/licenses/BSD-3-Clause")
  ),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/isomarcte/http4s-active-requests"),
      "scm:git:git@github.com:isomarcte/http4s-active-requests.git"
    )
  ),
  developers := List(
    Developer(
      "isomarcte",
      "David Strawn",
      "isomarcte@gmail.com",
      url("https://github.com/isomarcte")
    )
  ),
  credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  crossScalaVersions := scalaVersions
)

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
// Root Project //

lazy val root = (project in file("."))
  .settings(
    name := projectName,
    skip in publish := true
  )
  .aggregate(core)
  .settings(publishSettings: _*)

// Projects //

lazy val core = project
  .settings(
    name := s"$projectName-core",
    libraryDependencies ++= Seq(
      fs2Core,
      http4sServer,
      scalatest % Test
    ),
    addCompilerPlugin(
      "org.spire-math" % "kind-projector" % "0.9.9" cross CrossVersion.binary
    )
  )
  .settings(publishSettings: _*)
