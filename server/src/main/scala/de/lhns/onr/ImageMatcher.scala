package de.lhns.onr

import cats.effect.IO
import com.sksamuel.scrimage.color.RGBColor
import com.sksamuel.scrimage.pixels.Pixel
import com.sksamuel.scrimage.{ImmutableImage, Position, ScaleMethod}
import thirdparty.jhlabs.image.PixelUtils

import java.awt
import scala.util.chaining._

object ImageMatcher {
  def brightness(pixel: Pixel): Int =
    255 - ((255 - PixelUtils.brightness(pixel.argb)) * pixel.alpha() / 255)

  private val colorRequire = new RGBColor(0, 0, 0)
  private val colorIgnore = new RGBColor(255, 255, 255)
  private val colorDeny = new RGBColor(100, 100, 100)

  case class Stencil(image: ImmutableImage, numItems: Int) {
    var resizeCache: (Int, ImmutableImage) = (-1, null)

    def itemWidth(itemHeight: Int): Int =
      image.width * itemHeight * numItems / image.height

    def resize(itemHeight: Int): ImmutableImage = {
      if (resizeCache._1 != itemHeight) {
        val height = itemHeight * numItems
        val width = itemWidth(itemHeight)
        resizeCache = (itemHeight, image.bound(width + 1, height, ScaleMethod.FastScale))
      }
      resizeCache._2
    }

    def itemImage(itemHeight: Int, offset: Double, windowHeightFactor: Double = 1.0): ImmutableImage = {
      resize(itemHeight)
        .trimTop((offset * itemHeight).toInt)
        .pipe(image => image.takeTop(Math.min(image.height, (itemHeight * windowHeightFactor).toInt)))
    }

  }

  object Stencil {
    def prepare(stencilImage: ImmutableImage, numItems: Int, border: Int): IO[Stencil] = IO {
      stencilImage
        .pipe { image =>
          ImmutableImage.create(image.width, image.height)
            .fill(awt.Color.WHITE)
            .overlay(image)
            .resizeTo(image.width + border * 2, image.height, Position.Center, awt.Color.WHITE)
        }
        .pipe { image =>
          val newImage = image.copy()
          for {
            y <- 0 until image.height
            x <- 0 until image.width
            pixelBrightness = brightness(image.pixel(x, y))
          } {
            var minDist2 = Int.MaxValue

            {
              var yOff = -border
              while (yOff <= border && minDist2 > 1) {
                var xOff = -border
                while (xOff <= border && minDist2 > 1) {
                  val dist2 = xOff * xOff + yOff * yOff
                  if (dist2 < minDist2 &&
                    x + xOff > 0 && y + yOff > 0 && x + xOff < image.width && y + yOff < image.height &&
                    brightness(image.pixel(x + xOff, y + yOff)) != pixelBrightness)
                    minDist2 = dist2

                  xOff += 1
                }
                yOff += 1
              }
            }

            if (minDist2 != Int.MaxValue) {
              val dist = Math.sqrt(minDist2).toInt
              if (pixelBrightness == 255) {
                if (dist > 3)
                  newImage.setColor(x, y, colorDeny)
              } else {
                if (dist < 6)
                  newImage.setColor(x, y, colorIgnore)
              }
            }
          }

          Stencil(newImage, numItems)
        }
    }
  }

  def matchLoop(image: ImmutableImage,
                x: Int, y: Int,
                stencil: ImmutableImage,
                positive: (Int, Int) => Unit,
                negative: (Int, Int) => Unit): Unit = {
    for {
      yOff <- 0 until stencil.height
      xOff <- 0 until stencil.width
    } {
      val imagePixelBrightness = brightness(image.pixel(x + xOff, y + yOff))
      val stencilPixelBrightness = brightness(stencil.pixel(xOff, yOff))

      if (stencilPixelBrightness == colorRequire.red) {
        if (imagePixelBrightness == 0) {
          positive(x + xOff, y + yOff)
        } else {
          negative(x + xOff, y + yOff)
        }
      } else if (stencilPixelBrightness == colorDeny.red && imagePixelBrightness == 0) {
        negative(x + xOff, y + yOff)
      }
    }
  }

  // returns (score, num, x, y)
  def findBestScore(
                     image: ImmutableImage,
                     x: Int,
                     y: Int,
                     stencilAt: Double => ImmutableImage,
                     ignored: Set[(Int, Int)] = Set.empty,
                   ): IO[(Int, Double, Int, Int)] = {
    val multiplier = 30
    IO.parSequenceN(16)((for {
      numMultiplied <- 0 until (11 * multiplier) // TODO: stencil.numItems
      num = numMultiplied.toDouble / multiplier
      stencil = stencilAt(num)
      xOff <- -10 to 10
    } yield IO {
      var score: Long = 0
      var fails: Set[(Int, Int)] = Set.empty
      matchLoop(image, x + xOff, y, stencil,
        positive = (_, _) => {
          score += 1
        },
        negative = (x, y) => if (!ignored.contains((x, y))) {
          def failAtOffset(xOff: Int, yOff: Int): Int =
            if (fails.contains((x + xOff, y + yOff))) 1 else 0

          var otherFails: Int = 0

          {
            var y = -2
            while (y <= 2) {
              y += 1
              var x = -2
              while (x <= 2) {
                x += 1
                otherFails += failAtOffset(x, y)
              }
            }
          }

          fails += ((x, y))

          score -= 1 + otherFails
        },
      )
      (score.toInt, num, x + xOff, y)
    }).toList).map(_.maxBy(_._1))
  }
}
