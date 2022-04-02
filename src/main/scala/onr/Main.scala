package onr

import cats.effect.IO
import fs2._
import onr.color._

import java.nio.file.Paths

object Main {
  def main(args: Array[String]): Unit = {
    //val path = Paths.get("D:\\pierr\\Downloads\\stromzaehler.png")
    val path = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources\\IMG_20220401_185456.jpg")
    val path2 = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources\\IMG_20220401_185440.jpg")
    val path3 = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources\\WhatsApp Image 2022-04-01 at 15.30.21.jpeg")
    val path4 = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources\\WhatsApp Image 2022-04-02 at 14.50.57.jpeg")
    val image = Image.read(path4)

    def brightness(color: Color): Int = color.r + color.g + color.b

    def momochrome(image: Image, threshold: Int): Image = {
      val img = image.mutable

      for {
        y <- 0 until img.height
        x <- 0 until img.width
      } {
        val color = img.pixel(x, y)

        val newColor =
          if (brightness(color) > threshold)
            Color.Black
          else
            Color.White

        val newColor2 = {
          val gray = brightness(color) / 3
          Color(gray, gray, gray)
        }

        img.setPixel(x, y, newColor)
      }

      img.immutable
    }

    def avgBrightness(img: Image): Int = {
      var acc: Long = 0
      var i: Long = 0
      for {
        y <- 0 until img.height
        x <- 0 until img.width
      } {
        acc += brightness(img.pixel(x, y))
        i += 1
      }
      (acc / i).toInt
    }

    val newImage = momochrome(image, /*150*/ (avgBrightness(image) * 0.4).toInt)

    CanvasWindow("Test", 10, 10, Stream.repeatEval(IO(newImage))).show()
  }
}
