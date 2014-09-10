
organization := "com.gravitydev"

name := "trigger"

version := "0.0.10-SNAPSHOT"

scalaVersion := "2.11.2"

crossScalaVersions := Seq("2.11.2", "2.10.4")

libraryDependencies ++= Seq(
  "com.gravitydev" %% "awsutil" % "0.0.2-SNAPSHOT",
  "com.typesafe.play" %% "play-json" % "2.3.3",
  "com.typesafe.akka" %% "akka-actor" % "2.3.5",
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2"
)

resolvers += "gravitydev" at "https://devstack.io/repo/gravitydev/public"

publishTo := Some("gravitydev" at "https://devstack.io/repo/gravitydev/public")

