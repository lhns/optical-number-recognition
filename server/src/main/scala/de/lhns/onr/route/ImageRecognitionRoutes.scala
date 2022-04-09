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
import de.lhns.onr.repo.ParametersRepo
import de.lolhens.cats.effect.utils.CatsEffectUtils._
import de.lolhens.remoteio.Rest
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.log4s.getLogger

import java.awt
import java.nio.file.{Files, Paths}

class ImageRecognitionRoutes(config: Config,
                             parametersRepo: ParametersRepo[IO],
                             stencilIO: IO[Stencil],
                            ) {
  private val logger = getLogger

  private val cam = EspCam(config.espCam.uri, config.espCam.credentials)

  private val imageIO: IO[ImmutableImage] =
    JdkHttpClient.simple[IO].use { client =>
      cam.takePicture(client)
    }.map {
      case None =>
        throw new RuntimeException("Failed to take picture")
      case Some(bytes) =>
        ImageCleanup.cleanup(
          ImmutableImage.loader().fromBytes(bytes),
          thesholdFactor = 0.7
        ).max(800, 600)
    }.cacheForUnsafe(config.cacheDuration)

  private val imageIO2: IO[ImmutableImage] =
    IO.blocking(Files.readAllBytes(Paths.get("images/IMG_20220401_185456.jpg"))).map { bytes =>
      ImageCleanup.cleanup(
        ImmutableImage.loader().fromBytes(bytes),
        thesholdFactor = 0.7
      ).max(800, 600)
    }.memoizeUnsafe

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
        imageIO,
        parametersRepo.get,
        ).parTupled.flatMapOnChangeWithRef(ref) {
        case (image, parameters) =>
          val time = System.currentTimeMillis()
          logger.info("Detection started")
          detect(image, parameters).guaranteeCase {
            case Succeeded(_) =>
              IO(logger.info(s"Detection finished after ${System.currentTimeMillis() - time}"))

            case outcome =>
              IO(logger.error(s"Detection failed after ${System.currentTimeMillis() - time}: $outcome"))
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
    Api.detected.impl(_ => detectedIO.map(detectedNumber).map(_.toLong)),
    Api.previewImage.impl(_ => imageIO.map(e => ImageJpg(e.bytes(PngWriter.MinCompression)))),
    Api.previewDetected.impl { _ =>
      for {
        image <- imageIO
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
          _ <- IO(logger.info("Loading Image Recognition..."))
          stencil <- IO.defer {
            Stencil.prepare(
              ImmutableImage.loader().fromBytes(
                Files.readAllBytes(config.stencilPath)
              ),
              numItems = 11,
              border = 20,
            )
          }
          _ <- IO(logger.info("Loaded Image Recognition!"))
        } yield
          stencil).start)
    } yield
      new ImageRecognitionRoutes(
        config,
        parametersRepo,
        stencilFiber.joinWithNever,
      )
}
