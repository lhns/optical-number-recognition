ThisBuild / scalaVersion := "2.13.16"
ThisBuild / name := (server / name).value
name := (ThisBuild / name).value

val V = new {
  val catsEffect = "3.3.8"
  val catsEffectUtils = "0.2.0"
  val circe = "0.14.1"
  val circeConfig = "0.10.0"
  val fs2 = "3.9.4"
  val http4s = "0.23.30"
  val http4sDom = "0.2.0"
  val http4sJdkHttpClient = "0.9.2"
  val http4sSpa = "0.6.1"
  val logbackClassic = "1.4.14"
  val munit = "0.7.29"
  val munitTaglessFinal = "0.2.0"
  val remoteIo = "0.0.1"
  val scalajsDom = "2.1.0"
  val scalajsReact = "2.0.0"
  val scrimage = "4.1.3"
}

lazy val commonSettings: Seq[Setting[_]] = Seq(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "de.lolhens" %% "munit-tagless-final" % V.munitTaglessFinal % Test,
    "org.scalameta" %% "munit" % V.munit % Test,
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat",
  assembly / assemblyOption := (assembly / assemblyOption).value
    .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false))),
  assembly / assemblyMergeStrategy := {
    case PathList(paths@_*) if paths.last == "module-info.class" => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  },
)

lazy val root = project.in(file("."))
  .settings(
    publishArtifact := false
  )
  .aggregate(server)

lazy val common = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "de.lolhens" %%% "cats-effect-utils" % V.catsEffectUtils,
      "de.lolhens" %%% "remote-io-http4s" % V.remoteIo,
      "io.circe" %%% "circe-core" % V.circe,
      "io.circe" %%% "circe-generic" % V.circe,
      "io.circe" %%% "circe-parser" % V.circe,
      "org.http4s" %%% "http4s-circe" % V.http4s,
      "org.http4s" %%% "http4s-client" % V.http4s,
      "org.typelevel" %%% "cats-effect" % V.catsEffect,
    )
  )

lazy val commonJvm = common.jvm
lazy val commonJs = common.js

lazy val frontend = project
  .enablePlugins(ScalaJSWebjarPlugin)
  .dependsOn(commonJs)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core-bundle-cats_effect" % V.scalajsReact,
      "com.github.japgolly.scalajs-react" %%% "extra" % V.scalajsReact,
      "org.http4s" %%% "http4s-dom" % V.http4sDom,
      "org.scala-js" %%% "scalajs-dom" % V.scalajsDom,
    ),

    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
    scalaJSUseMainModuleInitializer := true,
  )

lazy val frontendWebjar = frontend.webjar
  .settings(
    webjarAssetReferenceType := Some("http4s"),
    libraryDependencies += "org.http4s" %% "http4s-server" % V.http4s,
  )

lazy val server = project
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(commonJvm, frontendWebjar)
  .settings(commonSettings)
  .settings(
    name := "optical-number-recognition",

    Compile / mainClass := Some("de.lhns.onr.Server"),

    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % V.logbackClassic,
      "co.fs2" %% "fs2-io" % V.fs2,
      "com.hunorkovacs" %% "circe-config" % V.circeConfig,
      "com.sksamuel.scrimage" %% "scrimage-scala" % V.scrimage,
      "com.sksamuel.scrimage" % "scrimage-filters" % V.scrimage,
      "de.lolhens" %% "http4s-spa" % V.http4sSpa,
      "de.lolhens" %% "remote-io-http4s" % V.remoteIo,
      "org.http4s" %% "http4s-ember-server" % V.http4s,
      "org.http4s" %% "http4s-dsl" % V.http4s,
      "org.http4s" %% "http4s-jdk-http-client" % V.http4sJdkHttpClient,
    )
  )
