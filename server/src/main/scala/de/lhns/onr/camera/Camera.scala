package de.lhns.onr.camera

import cats.Monad
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._
import com.sksamuel.scrimage.filter.ThresholdFilter
import com.sksamuel.scrimage.{ImmutableImage, MutableImage}
import thirdparty.jhlabs.image.PixelUtils

import java.awt
import scala.concurrent.duration.FiniteDuration

trait Camera[F[_]] {
  def takePicture: F[ImmutableImage]
}

object Camera {
  def fromImage[F[_] : Monad](image: ImmutableImage): Camera[F] = new Camera[F] {
    override def takePicture: F[ImmutableImage] = Monad[F].pure(image)
  }

  def fromImageBytes[F[_] : Monad](bytes: Array[Byte]): Camera[F] =
    fromImage(ImmutableImage.loader().fromBytes(bytes))

  def cache[F[_] : Async](
                           camera: Camera[F],
                           minDuration: FiniteDuration,
                           maxDuration: FiniteDuration
                         ): F[Camera[F]] = {
    for {
      semaphore <- Semaphore[F](1)
      ref <- Ref.of[F, Option[(ImmutableImage, FiniteDuration)]](None)
    } yield new Camera[F] {
      private def refreshRef(time: FiniteDuration): F[ImmutableImage] =
        for {
          newImage <- camera.takePicture
          _ <- ref.set(Some((newImage, time)))
        } yield
          newImage

      override def takePicture: F[ImmutableImage] = semaphore.permit.use { _ =>
        for {
          time <- Clock[F].realTime
          imageOption <- ref.get
          image <- imageOption match {
            case Some((_, cachedTime)) if time > (cachedTime + maxDuration) =>
              refreshRef(time)

            case Some((cachedImage, cachedTime)) if time > (cachedTime + minDuration) =>
              refreshRef(time)
                .start
                .as(cachedImage)

            case Some((cachedImage, _)) =>
              Async[F].pure(cachedImage)

            case None =>
              refreshRef(time)
          }
        } yield
          image
      }
    }
  }

  def threshold[F[_] : Monad](
                               camera: Camera[F],
                               threshold: ImmutableImage => F[Int],
                               invert: F[Boolean]
                             ): Camera[F] = new Camera[F] {
    override def takePicture: F[ImmutableImage] =
      for {
        image <- camera.takePicture
        thresholdValue <- threshold(image)
        invertValue <- invert
        black = awt.Color.BLACK.getRGB
        white = awt.Color.WHITE.getRGB
      } yield image.filter(new ThresholdFilter(
        thresholdValue,
        if (invertValue) black else white,
        if (invertValue) white else black
      ))
  }

  def averageBrightness(image: MutableImage, offset: Int = 0): Int = {
    var acc: Long = 0
    var i: Long = 0
    for {
      y <- 0 until image.height
      x <- 0 until image.width
    } {
      acc += PixelUtils.brightness(image.pixel(x, y).argb)
      i += 1
    }
    Math.min(Math.max((acc / i).toInt + offset, 0), 255)
  }

  def bounds[F[_] : Monad](camera: Camera[F], width: Int, height: Int): Camera[F] = new Camera[F] {
    override def takePicture: F[ImmutableImage] =
      camera.takePicture.map(_.max(width, height))
  }
}
