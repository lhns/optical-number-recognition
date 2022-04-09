package de.lhns.onr

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.IORuntime
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Request, Uri}
import org.log4s.getLogger

import scala.concurrent.duration._

case class EspCam(uri: Uri, credentials: Option[BasicCredentials]) {
  private val headers: Headers =
    credentials.fold(
      Headers.empty
    )(credentials =>
      Headers(Authorization(credentials))
    )

  def snapshot(client: Client[IO]): IO[Array[Byte]] =
    client.expect[Array[Byte]](Request[IO](
      uri = uri / "snapshot.jpg",
      headers = headers,
    ))

  private def runCommand(client: Client[IO], command: String): IO[String] =
    client.expect[String](Request[IO](
      uri = uri / "cm" +? ("cmnd", command),
      headers = headers,
    ))

  def setStreamEnabled(client: Client[IO], value: Boolean): IO[Unit] =
    runCommand(client, s"WcStream ${if (value) 1 else 0}").void

  def setLedEnabled(client: Client[IO], value: Boolean): IO[Unit] =
    runCommand(client, s"Power ${if (value) 1 else 0}").void

  private val logger = getLogger

  private val semaphore = Semaphore[IO](1).unsafeRunSync()(IORuntime.global)

  def takePicture(client: Client[IO]): IO[Option[Array[Byte]]] = semaphore.permit.use { _ =>
    IO(logger.info("Taking picture")) *>
      setLedEnabled(client, value = true) *>
      IO.sleep(100.millis) *>
      setStreamEnabled(client, value = true) *>
      IO.sleep(5.second) *>
      snapshot(client) *>
      snapshot(client) *>
      snapshot(client).map(Some(_).filterNot(_.isEmpty)) <*
      //setStreamEnabled(client, value = false) <*
      //IO.sleep(100.millis) <*
      setLedEnabled(client, value = false)
  }
}
