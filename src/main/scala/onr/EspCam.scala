package onr

import cats.effect.IO
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, Headers, Request, Uri}

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

  def takePicture(client: Client[IO]): IO[Array[Byte]] =
    setLedEnabled(client, value = true) *>
      setStreamEnabled(client, value = true) *>
      IO.sleep(5.second) *>
      snapshot(client) *>
      snapshot(client) *>
      snapshot(client) <*
      setStreamEnabled(client, value = false) <*
      setLedEnabled(client, value = false)
}
