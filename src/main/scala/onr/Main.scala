package onr

import java.nio.file.Paths

import fs2._
import onr.color._

object Main {
  def main(args: Array[String]): Unit = {
    val image = Image.read(Paths.get("D:\\pierr\\Downloads\\stromzaehler.png"))

    def momochrome(image: Image, threshold: Int): Image = {
      val img = image.mutable

      for {
        y <- 0 until img.height
        x <- 0 until img.width
      } {
        val color = img.pixel(x, y)

        val newColor =
          if (color.r + color.g + color.b > threshold)
            Color.Black
          else
            Color.White

        img.setPixel(x, y, newColor)
      }

      img.immutable
    }

    val newImage = momochrome(image, 150)

    CanvasWindow("Test", 10, 10, Stream(newImage)).show()
  }
}
