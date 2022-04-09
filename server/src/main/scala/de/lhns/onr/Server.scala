package de.lhns.onr

import cats.effect.{ExitCode, IO, IOApp, Resource}
import de.lhns.onr.repo.ParametersRepo
import de.lhns.onr.route.{ImageRecognitionRoutes, UiRoutes}
import io.circe.syntax._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Router

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object Server extends IOApp {


  override def run(args: List[String]): IO[ExitCode] = {
    val configPath = args.headOption.map(Paths.get(_)).getOrElse(throw new IllegalArgumentException("Missing command line parameter: config path"))

    def readConfig(): Config = {
      io.circe.config.parser.decode[Config](
        new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8)
      ).toTry.get
    }

    def writeConfig(config: Config): Unit = {
      Files.write(configPath, config.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
    }

    val config: Config = readConfig()

    val parametersRepo: ParametersRepo[IO] = new ParametersRepo[IO] {
      @volatile
      var cached: Parameters = config.parameters

      override def get: IO[Parameters] = IO.blocking {
        cached
      }

      override def set(parameters: Parameters): IO[Unit] = IO.blocking {
        if (parameters != cached) {
          cached = parameters
          writeConfig(config.copy(parameters = parameters))
        }
      }
    }

    applicationResource(config, parametersRepo).use(_ => IO.never)
  }

  private def applicationResource(config: Config, parametersRepo: ParametersRepo[IO]): Resource[IO, Unit] = {
    for {
      imageRecognitionRoutes <- ImageRecognitionRoutes(config, parametersRepo)
      uiRoutes = new UiRoutes()
      _ <- BlazeServerBuilder[IO]
        .withResponseHeaderTimeout(GlobalTimeout.timeout)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(Router(
          "/" -> uiRoutes.toRoutes,
          "/api" -> imageRecognitionRoutes.apiRoutes,
          "/metrics" -> imageRecognitionRoutes.metricsRoutes
        ).orNotFound)
        .resource
    } yield ()
  }
}
