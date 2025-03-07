package de.lhns.onr.route

import cats.effect.kernel.Resource
import cats.effect.{IO, Ref}
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.composite.{DifferenceComposite, SubtractComposite}
import com.sksamuel.scrimage.nio.PngWriter
import de.lhns.onr.Api.ImageJpg
import de.lhns.onr.Parameters.Rect
import de.lhns.onr._
import de.lhns.onr.camera.{Camera, EspCam}
import de.lhns.onr.repo.ParametersRepo
import de.lhns.onr.service.DetectionService
import de.lolhens.remoteio.Rest
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io._

import java.awt.Color
import scala.concurrent.duration._

class ImageRecognitionRoutes(
                              config: Config,
                              camera: Camera[IO],
                              detectionService: DetectionService[IO],
                              parametersRepo: ParametersRepo[IO]
                            ) {
  //private implicit val eqImage: Eq[ImmutableImage] = Eq.fromUniversalEquals
  //private implicit val eqParameters: Eq[Parameters] = Eq.fromUniversalEquals

  private val cacheRef = Ref.unsafe[IO, Option[(ImmutableImage, Parameters, String)]](None)

  private def trimImagePadded(image: ImmutableImage, parameters: Parameters): ImmutableImage = {
    val minX = parameters.ignored.map(_.x).minOption.getOrElse(0)
    val minY = parameters.ignored.map(_.y).minOption.getOrElse(0)
    val maxX = parameters.ignored.map(e => e.x + e.width).maxOption.getOrElse(image.width)
    val maxY = parameters.ignored.map(e => e.y + e.height).maxOption.getOrElse(image.height)
    image
      .subimage(minX, minY, maxX - minX, maxY - minY)
      .padLeft(minX, Color.BLACK).padTop(minY, Color.BLACK)
  }

  private def detectCached(image: ImmutableImage): IO[String] = {
    parametersRepo.get.flatMap { parameters =>
      cacheRef.get.flatMap {
        case Some((`image`, `parameters`, digits)) => IO(digits)
        case _ =>
          detectionService.detectDigits(image, parameters).flatMap { digits =>
            cacheRef.set(Some((image, parameters, digits))).map(_ => digits)
          }
      }
    }
  }

  private val lastImageRef = Ref.unsafe[IO, Option[ImmutableImage]](None)

  val apiRoutes: HttpRoutes[IO] = Rest.toRoutes(
    Api.getParameters.impl(_ => parametersRepo.get),
    Api.setParameters.impl(parametersRepo.set),
    Api.detected.impl(_ => camera.takePicture.flatMap(detectCached).map(_.toLong)),
    Api.previewImage.impl(_ => camera.takePicture.map(e => ImageJpg(e.bytes(PngWriter.MinCompression)))),
    Api.previewDetected.impl(_ => parametersRepo.get.flatMap(parameters =>
      camera.takePicture.map(trimImagePadded(_, parameters))/*.flatMap { img =>
        lastImageRef.get.flatMap {
          case Some(lastImage) =>
            val newImg = img.composite(new DifferenceComposite(1), lastImage)
            lastImageRef.set(Some(img)).as(newImg)

          case _ =>
            lastImageRef.set(Some(img)).as(img)
        }

      }*/
    ).map(e => ImageJpg(e.bytes(PngWriter.MinCompression)))),
  )

  val metricsRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root =>
      for {
        image <- camera.takePicture
        digits <- detectCached(image)
        number = BigDecimal(digits)
        decimalPlace = config.decimalPlace.getOrElse(0)
        metric = s"${config.metricName} ${(number / Math.pow(10, decimalPlace)).setScale(decimalPlace)}"
        response <- Ok(metric)
      } yield
        response
  }
}

object ImageRecognitionRoutes {
  def apply(
             client: Client[IO],
             config: Config,
             detectionService: DetectionService[IO],
             parametersRepo: ParametersRepo[IO]
           ): Resource[IO, ImageRecognitionRoutes] =
    for {
      camera <- Resource.pure(EspCam(
        client,
        config.espCam.uri,
        config.espCam.credentials
      ))
      camera <- Resource.pure(Camera.bounds(
        camera,
        800,
        600
      ))
      camera <- Resource.eval(Camera.cache(
        camera,
        15.seconds,
        1.minute
      ))
    } yield new ImageRecognitionRoutes(
      config,
      camera,
      detectionService,
      parametersRepo
    )
}
