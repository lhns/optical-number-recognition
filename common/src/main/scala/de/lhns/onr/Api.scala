package de.lhns.onr

import cats.effect.IO
import de.lolhens.remoteio.{Rest, Rpc}
import org.http4s.Method.{GET, POST}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.{EntityDecoder, EntityEncoder, MediaType}

object Api {
  private implicit val unitDecoder: EntityDecoder[IO, Unit] = EntityDecoder.void
  private implicit val unitEncoder: EntityEncoder[IO, Unit] = EntityEncoder.unitEncoder

  case class ImageJpg(bytes: Array[Byte])

  object ImageJpg {
    implicit val decoder: EntityDecoder[IO, ImageJpg] = EntityDecoder.byteArrayDecoder[IO].map(ImageJpg(_))
    implicit val encoder: EntityEncoder[IO, ImageJpg] =
      EntityEncoder.byteArrayEncoder[IO].withContentType(`Content-Type`(MediaType.image.jpeg)).contramap(_.bytes)
  }

  val getParameters = Rpc[IO, Unit, Parameters](Rest)(GET -> path"state")
  val setParameters = Rpc[IO, Parameters, Unit](Rest)(POST -> path"state")
  val detected = Rpc[IO, Unit, Long](Rest)(GET -> path"detected")
  val previewImage = Rpc[IO, Unit, ImageJpg](Rest)(GET -> path"preview/image")
  val previewDetected = Rpc[IO, Unit, ImageJpg](Rest)(GET -> path"preview/detected")
}
