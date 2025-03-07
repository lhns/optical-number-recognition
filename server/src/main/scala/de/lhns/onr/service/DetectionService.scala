package de.lhns.onr.service

import com.sksamuel.scrimage.ImmutableImage
import de.lhns.onr.Parameters

trait DetectionService[F[_]] {
  def detectDigits(image: ImmutableImage, parameters: Parameters): F[String]
}
