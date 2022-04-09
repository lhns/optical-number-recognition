package de.lhns.onr

import de.lhns.onr.Config.EspCamConfig
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder, Encoder}
import org.http4s.{BasicCredentials, Uri}

import java.nio.file.{Path, Paths}

case class Config(
                   stencilPath: Path,
                   espCam: EspCamConfig,
                   metricName: String,
                   decimalPlace: Option[Int],
                   parameters: Parameters,
                 )

object Config {
  private implicit val pathCodec: Codec[Path] = Codec.from(
    Decoder.decodeString.map(Paths.get(_)),
    Encoder.encodeString.contramap(_.toString)
  )

  implicit val codec: Codec[Config] = {
    case class ConfigSurrogate(
                                stencilPath: Path,
                                espCam: EspCamConfig,
                                metricName: String,
                                decimalPlace: Option[Int],
                                state: Option[Parameters],
                              )

    val codec: Codec[ConfigSurrogate] = deriveCodec
    Codec.from(
      codec.map(e => Config(e.stencilPath, e.espCam, e.metricName, e.decimalPlace, e.state.getOrElse(Parameters.empty))),
      codec.contramap(e => ConfigSurrogate(e.stencilPath, e.espCam, e.metricName, e.decimalPlace, Some(e.parameters)))
    )
  }

  case class EspCamConfig(
                           uri: Uri,
                           credentials: Option[BasicCredentials]
                         )

  object EspCamConfig {
    private implicit val uriCodec: Codec[Uri] = Codec.from(
      Decoder.decodeString.map(Uri.unsafeFromString),
      Encoder.encodeString.contramap(_.renderString)
    )

    private implicit val basicCredentialsCodec: Codec[BasicCredentials] = {
      case class BasicCredentialsSurrogate(
                                            username: String,
                                            password: String,
                                          )

      val codec = deriveCodec[BasicCredentialsSurrogate]
      Codec.from(
        codec.map(e => BasicCredentials(e.username, e.password)),
        codec.contramap(e => BasicCredentialsSurrogate(e.username, e.password))
      )
    }

    implicit val codec: Codec[EspCamConfig] = deriveCodec
  }
}
