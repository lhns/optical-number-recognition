package de.lhns.onr

import com.sksamuel.scrimage.filter.ThresholdFilter
import com.sksamuel.scrimage.{ImmutableImage, MutableImage}
import thirdparty.jhlabs.image.PixelUtils

import java.awt
import scala.util.chaining._

object ImageCleanup {
  def cleanup(image: ImmutableImage, thesholdFactor: Double): ImmutableImage = {
    image
      .pipe { image =>
        val thresh = avgBrightness(image)
        image.filter(new ThresholdFilter((thresh * thesholdFactor).toInt, awt.Color.BLACK.getRGB, awt.Color.WHITE.getRGB))
      }
  }

  def avgBrightness(image: MutableImage): Int = {
    var acc: Long = 0
    var i: Long = 0
    for {
      y <- 0 until image.height
      x <- 0 until image.width
    } {
      acc += PixelUtils.brightness(image.pixel(x, y).argb)
      i += 1
    }
    (acc / i).toInt
  }
}
