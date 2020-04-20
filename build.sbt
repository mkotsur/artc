organization := "io.github.mkotsur"
ThisBuild / name := "artc"

// ThisBuild / version := @see version.sbt

ThisBuild / scalaVersion := "2.13.1"

ThisBuild / libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.3"
ThisBuild / libraryDependencies += "io.chrisdavenport" %% "log4cats-slf4j" % "1.0.1"
ThisBuild / libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % "test"
ThisBuild / libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3" % "test"
ThisBuild / libraryDependencies += "com.codecommit" %% "cats-effect-testing-scalatest" % "0.4.0" % "test"

import ReleaseTransformations._

lazy val `artc` =
  (project in file(".")).settings(
    releaseCrossBuild := true,
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
      releaseStepCommand("sonatypeReleaseAll"),
      pushChanges
    ),
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    publishTo := Some(
      if (isSnapshot.value)
        Opts.resolver.sbtIvySnapshots
      else
        Opts.resolver.sonatypeStaging
    )
  )
