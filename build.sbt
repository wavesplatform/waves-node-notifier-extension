name := "mining-notifier"
version := "0.1"

scalaVersion := "2.12.8"
val nodeVersion = "v1.0.2"

lazy val node = ProjectRef( uri(s"git://github.com/wavesplatform/Waves.git#$nodeVersion"), "node")

lazy val myProject = (project in file("."))
  .dependsOn(node % "compile;runtime->provided")

libraryDependencies += "org.scalaj" %% "scalaj-http" % "2.4.2"
