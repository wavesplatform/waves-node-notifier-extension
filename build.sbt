name := "mining-notifier"
version := "0.3.4"

scalaVersion := "2.12.9"
val nodeVersion = "v1.1.2"

lazy val node = ProjectRef(uri(s"git://github.com/wavesplatform/Waves.git#$nodeVersion"), "node")

lazy val myProject = (project in file("."))
  .dependsOn(node % "compile;runtime->provided")
  .settings(extSettings)

lazy val extSettings = Seq(
  libraryDependencies ++= {
    val quill = Seq(
      "org.postgresql" % "postgresql" % "9.4.1208",
      "io.getquill" %% "quill-jdbc" % "3.1.0",
      "com.h2database" % "h2" % "1.4.192"
    )

    val http = "org.scalaj" %% "scalaj-http" % "2.4.2"
    quill :+ http
  }
)
