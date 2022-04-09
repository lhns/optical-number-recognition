package de.lhns.onr.repo

import cats.effect.IO
import de.lhns.onr.Parameters

trait ParametersRepo[F[_]] {
  def get: F[Parameters]

  def set(parameters: Parameters): IO[Unit]
}
