package de.lhns.onr.camera

import cats.Monad
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.GrayscaleMethod

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

  def grayscale[F[_] : Monad](camera: Camera[F]): Camera[F] = new Camera[F] {
    override def takePicture: F[ImmutableImage] =
      camera.takePicture.map(_.toGrayscale(GrayscaleMethod.AVERAGE))
  }

  def bounds[F[_] : Monad](camera: Camera[F], width: Int, height: Int): Camera[F] = new Camera[F] {
    override def takePicture: F[ImmutableImage] =
      camera.takePicture.map(_.max(width, height))
  }
}
