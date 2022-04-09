package de.lhns.onr

import cats.effect.IO
import de.lolhens.remoteio.Rest.RestClientImpl
import org.http4s.dom.FetchClientBuilder
import org.http4s.implicits._

package object frontend {
  implicit val restClient: RestClientImpl[IO] = RestClientImpl[IO](
    FetchClientBuilder[IO].withRequestTimeout(GlobalTimeout.timeout).create,
    uri"/api"
  )
}
