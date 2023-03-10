package de.lhns.onr.route

import cats.Eq
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.{Deferred, Resource}
import cats.effect.std.Semaphore
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Ref}
import cats.syntax.parallel._
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.RGBColor
import com.sksamuel.scrimage.nio.PngWriter
import de.lhns.onr.Api.ImageJpg
import de.lhns.onr.ImageMatcher.Stencil
import de.lhns.onr.Parameters.Vec2
import de.lhns.onr._
import de.lhns.onr.camera.{Camera, EspCam}
import de.lhns.onr.repo.ParametersRepo
import de.lolhens.cats.effect.utils.CatsEffectUtils._
import de.lolhens.remoteio.Rest
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.log4s.getLogger

import java.awt
import java.nio.file.Files
import scala.concurrent.duration._

class ImageRecognitionRoutes(config: Config,
                             camera: Camera[IO],
                             parametersRepo: ParametersRepo[IO],
                             stencilIO: IO[Stencil],
                            ) {
  private val logger = getLogger

  case class DetectedDigit(x: Int, y: Int, num: Double)

  private def detect(image: ImmutableImage, parameters: Parameters): IO[Seq[DetectedDigit]] = {
    for {
      stencil <- stencilIO
      charWidth = stencil.itemWidth(parameters.height)
      ignored = parameters.ignoredPixels
      detectedDigits <- IO.parSequenceN(8) {
        parameters.vectors.map {
          case Vec2(x, y) =>
            ImageMatcher.findBestScore(
              image, x - (charWidth + 1) / 2, y - (parameters.height + 1) / 2,
              stencilAt = num => stencil.itemImage(parameters.height, num),
              ignored = ignored,
            ).map {
              case (score, num, x, y) =>
                DetectedDigit(x, y, num)
            }
        }
      }
    } yield
      detectedDigits
  }

  private implicit val eqImage: Eq[ImmutableImage] = Eq.fromUniversalEquals
  private implicit val eqParameters: Eq[Parameters] = Eq.fromUniversalEquals

  private val detectionSemaphore = Semaphore[IO](1).unsafeRunSync()(IORuntime.global)

  val detectedIO: IO[Seq[DetectedDigit]] = {
    val ref = Ref.unsafe[IO, Option[((ImmutableImage, Parameters), Deferred[IO, Option[Seq[DetectedDigit]]])]](None)
    detectionSemaphore.permit.use { _ =>
      (
        camera.takePicture,
        parametersRepo.get,
        ).parTupled.flatMapOnChangeWithRef(ref) {
        case (image, parameters) =>
          val time = System.currentTimeMillis()
          logger.info("detection started")
          detect(image, parameters).guaranteeCase {
            case Succeeded(_) =>
              IO(logger.info(s"detection finished after ${System.currentTimeMillis() - time}"))

            case outcome =>
              IO(logger.error(s"detection failed after ${System.currentTimeMillis() - time}: $outcome"))
          }
      }
    }.uncancelable
  }

  def detectionPreview(image: ImmutableImage, detectedDigits: Seq[DetectedDigit]): IO[ImmutableImage] = {
    for {
      image <- IO(image.copy())
      stencil <- stencilIO
      parameters <- parametersRepo.get
      _ = for (detectedDigit <- detectedDigits) {
        ImageMatcher.matchLoop(
          image,
          detectedDigit.x, detectedDigit.y,
          stencil = stencil.itemImage(parameters.height, detectedDigit.num),
          positive = (x, y) => image.setColor(x, y, RGBColor.fromAwt(awt.Color.GREEN)),
          negative = (x, y) => image.setColor(x, y, RGBColor.fromAwt(awt.Color.RED))
        )
      }
    } yield
      image
  }

  def detectedNumber(detectedDigits: Seq[DetectedDigit]): Long = {
    val orderedDigits = detectedDigits.sortBy(_.x).map(_.num)
    orderedDigits.foldRight("") {
      case (digit, string) =>
        val roundedDigit: Int = Math.round(digit).toInt % 10
        roundedDigit + string
    }.toLong
  }

  val apiRoutes: HttpRoutes[IO] = Rest.toRoutes(
    Api.getParameters.impl(_ => parametersRepo.get),
    Api.setParameters.impl(parametersRepo.set),
    Api.detected.impl(_ => detectedIO.map(detectedNumber)),
    Api.previewImage.impl(_ => camera.takePicture.map(e => ImageJpg(e.bytes(PngWriter.MinCompression)))),
    Api.previewDetected.impl { _ =>
      for {
        image <- camera.takePicture
        detected <- detectedIO
        preview <- detectionPreview(image, detected)
      } yield
        ImageJpg(preview.bytes(PngWriter.MinCompression))
    },
  )

  val metricsRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root =>
      for {
        detected <- detectedIO
        number = BigDecimal(detectedNumber(detected))
        decimalPlace = config.decimalPlace.getOrElse(0)
        metric = s"${config.metricName} ${(number / Math.pow(10, decimalPlace)).setScale(decimalPlace)}"
        response <- Ok(metric)
      } yield
        response
  }
}

object ImageRecognitionRoutes {
  private val logger = getLogger

  def apply(config: Config, parametersRepo: ParametersRepo[IO]): Resource[IO, ImageRecognitionRoutes] =
    for {
      stencilFiber <- Resource.eval((
        for {
          _ <- IO(logger.info("loading image recognition..."))
          stencil <- IO.defer {
            Stencil.prepare(
              ImmutableImage.loader().fromBytes(
                Files.readAllBytes(config.stencilPath)
              ),
              numItems = 11,
              border = 20,
            )
          }
          _ <- IO(logger.info("loaded image recognition!"))
        } yield
          stencil).start)
      client <- JdkHttpClient.simple[IO]
      camera = EspCam(
        client,
        config.espCam.uri,
        config.espCam.credentials
      )
      camera <- Resource.pure(Camera.threshold(
        camera,
        image => IO(Camera.averageBrightness(image)),
        invert = IO(true)
      ))
      camera <- Resource.pure(Camera.bounds(
        camera,
        800,
        600
      ))
      camera <- Resource.eval(Camera.cache(
        camera,
        10.seconds,
        1.minute
      ))
    } yield new ImageRecognitionRoutes(
      config,
      camera,
      parametersRepo,
      stencilFiber.joinWithNever,
    )
}
