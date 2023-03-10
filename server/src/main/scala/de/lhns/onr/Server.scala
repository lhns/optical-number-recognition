package de.lhns.onr

import cats.effect._
import com.comcast.ip4s._
import de.lhns.onr.repo.ParametersRepo
import de.lhns.onr.route.{ImageRecognitionRoutes, UiRoutes}
import io.circe.syntax._
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorAction
import org.http4s.server.{Router, Server}
import org.log4s.getLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.concurrent.duration._

object Server extends IOApp {
  private val logger = getLogger

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

  private def applicationResource(config: Config, parametersRepo: ParametersRepo[IO]): Resource[IO, Unit] =
    for {
      imageRecognitionRoutes <- ImageRecognitionRoutes(config, parametersRepo)
      uiRoutes = new UiRoutes()
      _ <- serverResource(
        SocketAddress(host"0.0.0.0", port"8080"),
        Router(
          "/" -> uiRoutes.toRoutes,
          "/api" -> imageRecognitionRoutes.apiRoutes,
          "/metrics" -> imageRecognitionRoutes.metricsRoutes
        ).orNotFound
      )
    } yield ()

  def serverResource[F[_] : Async](socketAddress: SocketAddress[Host], http: HttpApp[F]): Resource[F, Server] =
    EmberServerBuilder.default[F]
      .withHost(socketAddress.host)
      .withPort(socketAddress.port)
      .withHttpApp(ErrorAction.log(
        http = http,
        messageFailureLogAction = (t, msg) => Async[F].delay(logger.debug(t)(msg)),
        serviceErrorLogAction = (t, msg) => Async[F].delay(logger.error(t)(msg))
      ))
      .withShutdownTimeout(1.second)
      .build
}
