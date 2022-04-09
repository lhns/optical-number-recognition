package de.lhns.onr

import de.lhns.onr.Parameters.{Rect, Vec2}
import io.circe.Codec
import io.circe.generic.semiauto._

case class Parameters(
                       height: Int,
                       vectors: Seq[Vec2],
                       ignored: Seq[Rect],
                     ) {
  lazy val ignoredPixels: Set[(Int, Int)] = ignored.toSet.flatMap((rect: Rect) => rect.pixels)
}

object Parameters {
  val empty: Parameters = Parameters(
    height = 10,
    vectors = Seq.empty,
    ignored = Seq.empty,
  )

  implicit val codec: Codec[Parameters] = deriveCodec

  case class Vec2(x: Int, y: Int) {
    def dist2(x: Int, y: Int): Int = {
      val xOff = x - this.x
      val yOff = y - this.y
      xOff * xOff + yOff * yOff
    }
  }

  object Vec2 {
    implicit val codec: Codec[Vec2] = deriveCodec
  }

  case class Rect(x: Int, y: Int, width: Int, height: Int) {
    lazy val center: Vec2 = Vec2(
      x + width / 2,
      y + height / 2,
    )

    lazy val topLeftHandle: Vec2 = Vec2(x, y)
    lazy val bottomRightHandle: Vec2 = Vec2(x + width, y + height)

    def moveTopLeftHandle(x: Int, y: Int): Rect = Rect(
      x,
      y,
      this.x + width - x,
      this.y + height - y,
    )

    def moveBottomRightHandle(x: Int, y: Int): Rect = Rect(
      this.x,
      this.y,
      x - this.x,
      y - this.y,
    )

    lazy val nonNegative: Option[Rect] =
      if (width >= 0 || height >= 0) None
      else Some(Rect(
        if (width >= 0) x else x + width,
        if (height >= 0) y else x + height,
        if (width >= 0) width else -width,
        if (height >= 0) height else -height,
      ))

    lazy val pixels: Set[(Int, Int)] =
      nonNegative.fold {
        (for {
          yOff <- 0 until height
          xOff <- 0 until width
        } yield
          (x + xOff, y + yOff)).toSet
      }(_.pixels)
  }

  object Rect {
    implicit val codec: Codec[Rect] = deriveCodec
  }
}
