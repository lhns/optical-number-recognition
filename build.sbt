name := "optical-number-recognition"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.8"

def osName: String =
  if (scala.util.Properties.isLinux) "linux"
  else if (scala.util.Properties.isMac) "mac"
  else if (scala.util.Properties.isWin) "win"
  else throw new Exception("Unknown platform!")

val V = new {
  val catsEffect = "3.3.8"
  val fs2 = "3.2.5"
  val scrimage = "4.0.31"
}

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % V.fs2,
  "co.fs2" %% "fs2-io" % V.fs2,
  "com.sksamuel.scrimage" %% "scrimage-scala" % V.scrimage,
  "com.sksamuel.scrimage" % "scrimage-filters" % V.scrimage,
  "org.bytedeco" % "javacv-platform" % "1.5.7",
  "org.http4s" %% "http4s-jdk-http-client" % "0.5.0",
  "org.typelevel" %% "cats-effect" % V.catsEffect,
  "org.openjfx" % "javafx-base" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-controls" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-graphics" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-media" % "14.0.1" classifier osName,
  "org.openjfx" % "javafx-swing" % "14.0.1" classifier osName,
  "org.scalafx" %% "scalafx" % "14-R19"
)
