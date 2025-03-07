package de.lhns.onr.service

import cats.effect.Async
import cats.syntax.all._
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import de.lhns.onr.Parameters
import fs2.Chunk
import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder}
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}
import cats.effect.syntax.all._
import org.log4s.getLogger

import java.awt.Color
import java.time.OffsetDateTime
import java.util.Base64

class DetectionServiceAiImpl[F[_] : Async : Client](ollamaApiUri: Uri) extends DetectionService[F] {
  private case class Message(
                              role: String,
                              content: String,
                              images: Option[Seq[Chunk[Byte]]]
                            )

  private implicit val base64Codec: Codec[Chunk[Byte]] = Codec.from(
    Decoder.decodeString.map(string => Chunk.array(Base64.getDecoder.decode(string))),
    Encoder.encodeString.contramap(bytes => Base64.getEncoder.encodeToString(bytes.toArray))
  )

  private implicit val messageCodec: Codec[Message] = deriveCodec

  private case class ChatRequest(
                                  model: String,
                                  messages: Seq[Message]
                                )

  private implicit val chatRequestEncoder: Encoder[ChatRequest] = deriveEncoder

  private case class ChatResponse(
                                   model: String,
                                   created_at: OffsetDateTime,
                                   message: Message,
                                   done: Boolean,
                                   done_reason: Option[String]
                                 )

  private implicit val chatResponseDecoder: Decoder[ChatResponse] = deriveDecoder

  private val logger = getLogger

  override def detectDigits(image: ImmutableImage, parameters: Parameters): F[String] = {
    val digitImages = parameters.ignored.sortBy(_.x).map { digitRect =>
      val size = Math.max(digitRect.width, digitRect.height) * 2
      image.subimage(digitRect.x, digitRect.y, digitRect.width, digitRect.height).padTo(size, size, Color.BLACK).scale(2)
    }

    logger.info(s"detecting digits")
    digitImages.zipWithIndex.map { case (digitImage, i) =>
      Async[F].defer {
        val time = System.currentTimeMillis()
        detectDigit(Seq(digitImage))
          .handleError(error => throw new RuntimeException(s"failed to parse digit $i", error))
          .map { digitString =>
            val time2 = System.currentTimeMillis()
            logger.info(s"detected digit $i after ${time2 - time} ms")
            digitString.getOrElse(0).toString
          }
      }
    }.parSequenceN(1).map(_.mkString)
    /*
        Async[F].defer {
          val time = System.currentTimeMillis()
          logger.info(s"detecting image")
          detectDigit(digitImages).map { digitString =>
            val time2 = System.currentTimeMillis()
            logger.info(s"detected digits after ${time2 - time} ms")
            (digitString.replaceAll("[^\\d]", "") + "0").take(1)
          }
        }*/
  }

  def detectDigit(images: Seq[ImmutableImage]): F[Option[Int]] = {
    for {
      imagesBytes <- Async[F].delay(images.map(_.bytes(PngWriter.MinCompression)))
      json = ChatRequest(
        model = "granite3.2-vision",
        messages = imagesBytes.map { imageBytes =>
          Message(
            role = "user",
            content = "The image shows a mechanical counter with a digit wheel. Tell the most probable digit and just the digit.",
            images = Some(Seq(Chunk.array(imageBytes)))
          )
        }
      )
      request = Request[F](
        method = Method.POST,
        uri = ollamaApiUri
      ).withEntity(json.asJson)
      jsonLines <- implicitly[Client[F]].expectOr[String](request) { response =>
        response.as[String].map(error => new RuntimeException(error))
      }
      responses = jsonLines.split("\r?\n").filter(_.nonEmpty).map(line => io.circe.parser.decode[ChatResponse](line).toTry.get)
      responseString = responses.filter(_.message.role == "assistant").map(_.message.content).mkString.trim
      digit = Some(responseString.replaceAll("[^\\d]", "").take(1)).filter(_.nonEmpty).map(_.toInt)
      _ = if (digit.isEmpty && responseString.contains("low resolution")) throw new RuntimeException(s"failed to parse digit: $responseString")
      _ = println(responseString)
    } yield
      digit
  }
}
