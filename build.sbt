name := "optical-number-recognition"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.2"

def osName: String =
  if (scala.util.Properties.isLinux) "linux"
  else if (scala.util.Properties.isMac) "mac"
  else if (scala.util.Properties.isWin) "win"
  else throw new Exception("Unknown platform!")

libraryDependencies ++= Seq(
  "io.monix" %% "monix" % "3.2.2",
  "co.fs2" %% "fs2-core" % "2.4.1",
  "co.fs2" %% "fs2-io" % "2.4.1",
  "org.openjfx" % "javafx-base" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-controls" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-graphics" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-media" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-swing" % "14.0.1" classifier osName,
  "org.scalafx" %% "scalafx" % "14-R19"
)
