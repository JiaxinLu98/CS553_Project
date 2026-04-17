import sbt.Keys.libraryDependencies

ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "edu.uic.cs553"

val akkaVersion = "2.8.5"
val scalaTestVersion = "3.2.17"
val typeSafeConfigVersion = "1.4.3"
val logbackVersion = "1.4.11"
val circeVersion = "0.14.6"

lazy val commonDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion cross CrossVersion.for3Use2_13,
  "com.typesafe" % "config" % typeSafeConfigVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "org.slf4j" % "slf4j-api" % "2.0.9",
  "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test cross CrossVersion.for3Use2_13
)

lazy val root = (project in file("."))
  .settings(
    name := "NetGameSimAkka"
  )
  .aggregate(simCore, simRuntimeAkka, simAlgorithms, simCli)

lazy val simCore = (project in file("sim-core"))
  .settings(
    name := "sim-core",
    libraryDependencies ++= commonDependencies ++ Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion, // not used
      "io.circe" %% "circe-parser" % circeVersion
    )
  )

lazy val simRuntimeAkka = (project in file("sim-runtime-akka"))
  .settings(
    name := "sim-runtime-akka",
    libraryDependencies ++= commonDependencies
  )
  .dependsOn(simCore)

lazy val simAlgorithms = (project in file("sim-algorithms"))
  .settings(
    name := "sim-algorithms",
    libraryDependencies ++= commonDependencies
  )
  .dependsOn(simRuntimeAkka)

lazy val simCli = (project in file("sim-cli"))
  .settings(
    name := "sim-cli",
    libraryDependencies ++= commonDependencies,
    run / fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    run / javaOptions ++= Seq("-Xms512M", "-Xmx4G")
  )
  .dependsOn(simAlgorithms)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked"
)
