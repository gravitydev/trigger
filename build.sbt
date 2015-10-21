
organization := "com.gravitydev"

name := "trigger"

version := "0.1.3-SNAPSHOT"

scalaVersion := "2.11.6"

crossScalaVersions := Seq("2.11.7")

libraryDependencies ++= Seq(
  "com.gravitydev" %% "awsutil" % "0.0.3-SNAPSHOT",
  "com.typesafe.play" %% "play-json" % "2.3.10",
  "com.typesafe.akka" %% "akka-actor" % "2.3.13",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"
)

resolvers += "gravitydev" at "https://devstack.io/repo/gravitydev/public"

publishTo := Some("gravitydev" at "https://devstack.io/repo/gravitydev/public")

