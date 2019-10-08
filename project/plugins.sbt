lazy val projPlugins = Seq(
  "org.scalameta" % "sbt-scalafmt" % "2.0.1"
)

projPlugins.map(addSbtPlugin)
