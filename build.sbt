ThisBuild / name := "arts"

ThisBuild / version := "0.1"

ThisBuild / scalaVersion := "2.13.1"

ThisBuild / libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.2"
ThisBuild / libraryDependencies += "io.chrisdavenport" %% "log4cats-slf4j" % "1.0.0"

ThisBuild / libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.1" % "test"
ThisBuild / libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3" % "test"
ThisBuild / libraryDependencies += "com.codecommit" %% "cats-effect-testing-scalatest" % "0.4.0" % "test"

// Sub-projects
lazy val `artc` =
  (project in file("."))
