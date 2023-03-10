package de.lhns.onr.camera

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.IORuntime
import com.sksamuel.scrimage.ImmutableImage
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Request, Uri}
import org.log4s.getLogger

import scala.concurrent.duration._

case class EspCam(
                   client: Client[IO],
                   uri: Uri,
                   credentials: Option[BasicCredentials]
                 ) extends Camera[IO] {
  private val logger = getLogger

  private val headers: Headers =
    credentials.fold(
      Headers.empty
    )(credentials =>
      Headers(Authorization(credentials))
    )

  private def runCommand(client: Client[IO], command: String): IO[String] =
    client.expect[String](Request[IO](
      uri = uri / "cm" +? ("cmnd", command),
      headers = headers,
    ))

  def takeSnapshot(client: Client[IO]): IO[Array[Byte]] =
    client.expect[Array[Byte]](Request[IO](
      uri = uri / "snapshot.jpg",
      headers = headers,
    ))

  def setStreamEnabled(client: Client[IO], value: Boolean): IO[Unit] =
    runCommand(client, s"WcStream ${if (value) 1 else 0}").void

  def setLedEnabled(client: Client[IO], value: Boolean): IO[Unit] =
    runCommand(client, s"Power ${if (value) 1 else 0}").void

  private val semaphore = Semaphore[IO](1).unsafeRunSync()(IORuntime.global)

  def takePicture: IO[ImmutableImage] = semaphore.permit.use { _ =>
    for {
      _ <- IO(logger.info("taking picture"))
      time <- IO.realTimeInstant
      _ <- setLedEnabled(client, value = true)
      _ <- IO.sleep(100.millis)
      _ <- setStreamEnabled(client, value = true)
      _ <- IO.sleep(5.second)
      _ <- takeSnapshot(client)
      _ <- takeSnapshot(client)
      snapshotBytes <- takeSnapshot(client)
      _ = if (snapshotBytes.isEmpty) throw new RuntimeException("failed to take picture")
      _ <- setLedEnabled(client, value = false)
    } yield
      ImmutableImage.loader().fromBytes(snapshotBytes)
  }
}
