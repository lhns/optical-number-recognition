package onr

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.sksamuel.scrimage.color.RGBColor
import com.sksamuel.scrimage.pixels.Pixel
import com.sksamuel.scrimage.{ImmutableImage, Position, ScaleMethod}
import fs2._
import org.http4s.Uri
import org.http4s.jdkhttpclient.JdkHttpClient
import thirdparty.jhlabs.image.PixelUtils

import java.awt
import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.util.chaining._

object Main {
  def main(args: Array[String]): Unit = {
    //val path = Paths.get("D:\\pierr\\Downloads\\stromzaehler.png")
    val dir = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources")
    val path = dir.resolve("IMG_20220401_185456.jpg")
    val path2 = dir.resolve("IMG_20220401_185440.jpg")
    val path3 = dir.resolve("WhatsApp Image 2022-04-01 at 15.30.21.jpeg")
    val path4 = dir.resolve("WhatsApp Image 2022-04-02 at 14.50.57.jpeg")
    //val image = Image.read(path3).rect(100, 600, 800, 400)

    def brightness(pixel: Pixel): Int = 255 - ((255 - PixelUtils.brightness(pixel.argb)) * pixel.alpha() / 255)

    val distance = 20

    val numberwheel = ImmutableImage.loader().fromPath(dir.resolve("numberwheel.png"))
      .pipe { image =>
        ImmutableImage.create(image.width, image.height)
          .fill(awt.Color.WHITE)
          .overlay(image)
          .resizeTo(image.width + distance * 2, image.height, Position.Center, awt.Color.WHITE)
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
            var yOff = -distance
            while (yOff <= distance && minDist2 > 1) {
              var xOff = -distance
              while (xOff <= distance && minDist2 > 1) {
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
                newImage.setColor(x, y, new RGBColor(100, 100, 100))
            } else {
              if (dist < 6)
                newImage.setColor(x, y, new RGBColor(255, 255, 255))
            }
          }
        }
        newImage
      }

    var resizedNumerwheelCached: ImmutableImage = null

    def resizedNumberwheel(charHeight: Int): ImmutableImage =
      Option(resizedNumerwheelCached).getOrElse {
        val height = charHeight * 11
        val width = numberwheel.width * height / numberwheel.height + 1
        resizedNumerwheelCached = numberwheel
          .bound(width, height, ScaleMethod.FastScale)
        resizedNumerwheelCached
      }

    val heightFactor = 1.0 // 1.2

    def charStencil(charHeight: Int, offset: Double, heightFactor: Double): ImmutableImage = {
      val numberwheel = resizedNumberwheel(charHeight)
      numberwheel.trimTop((offset * charHeight).toInt).pipe(img => img.takeTop(Math.min(img.height, (charHeight * heightFactor).toInt)))
    }

    val uri = Uri.unsafeFromString("http://10.1.15.21/snapshot.jpg")
    val client = JdkHttpClient.simple[IO].allocated.unsafeRunSync()(IORuntime.global)._1
    val bytes = {
      client.expect[Array[Byte]](uri) *>
        client.expect[Array[Byte]](uri) *>
        client.expect[Array[Byte]](uri)
    }.unsafeRunSync()(IORuntime.global)

    val image = ImageCleanup.cleanup(ImmutableImage.loader().fromBytes(bytes), 0.7).max(800, 600)
    //resizedNumberwheel(80)
    //.trim(20, 600, 20, 800)
    //.trim(350, 250, 350, 100)

    def matchLoop(image: ImmutableImage, x: Int, y: Int, stencil: ImmutableImage, positive: (Int, Int) => Unit, negative: (Int, Int) => Unit): Unit = {
      for {
        yOff <- 0 until stencil.height
        xOff <- 0 until stencil.width
      } {
        val imagePixelBrightness = brightness(image.pixel(x + xOff, y + yOff))
        val stencilPixelBrightness = brightness(stencil.pixel(xOff, yOff))

        if (stencilPixelBrightness == 0) {
          if (imagePixelBrightness == 0) {
            positive(x + xOff, y + yOff)
          } else {
            negative(x + xOff, y + yOff)
          }
        } else if (stencilPixelBrightness == 100 && imagePixelBrightness == 0) {
          negative(x + xOff, y + yOff)
        }
      }
    }

    var ignored: Set[(Int, Int)] = Set.empty

    def findBestScore(image: ImmutableImage, x: Int, y: Int, charHeight: Int): (Int, Double, Int, Int) = {
      val multiplier = 30
      IO.parSequenceN(16)((for {
        numMultiplied <- 0 until (11 * multiplier)
        num = numMultiplied.toDouble / multiplier
        stencil = charStencil(charHeight, num, heightFactor)
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
        (score.toInt, num % 10, x + xOff, y)
      }).toList).unsafeRunSync()(IORuntime.global).maxBy(_._1)
    }

    @volatile
    var renderedImage = image

    var digits: Seq[(Int, Double)] = Seq.empty

    def digitsToNumber(digits: Seq[(Int, Double)]): Long = {
      val orderedDigits = digits.sortBy(_._1).map(_._2)
      orderedDigits.foldRight(("", 0D)) {
        case (digit, (string, lessSignificantDigit)) =>
          val roundedDigit: Int = Math.round(digit).toInt
          (roundedDigit + string, digit)
      }._1.toLong
    }

    val window = CanvasWindow("Test", 10, 10, Stream.every[IO](1.seconds).evalMap(_ => IO {
      renderedImage
    }))
    val charHeight = 75 //67
    window.onClickStream.evalMap { event =>
      if (event.isShiftDown) IO {
        val image = renderedImage.copy()
        val ex = event.getX.toInt
        val ey = event.getY.toInt
        for {
          yOff <- -5 to 5
          xOff <- -5 to 5
        } {
          ignored = ignored + ((ex + xOff, ey + yOff))
          image.setColor(ex + xOff, ey + yOff, RGBColor.fromAwt(awt.Color.BLUE))
        }
        renderedImage = image
      } else IO {
        val charWidth = numberwheel.width * charHeight * 11 / numberwheel.height

        val ex = event.getX.toInt - (charWidth + 1) / 2
        val ey = event.getY.toInt

        val (_, num, x, y) = findBestScore(image, ex, ey, charHeight)
        println(num)
        digits = digits :+ ((x, num))
        println(digitsToNumber(digits))

        val numbers = charStencil(charHeight, num, heightFactor)
        val newRenderedImage = renderedImage.overlay(numbers, x, y)

        matchLoop(image, x, y, charStencil(charHeight, num, heightFactor),
          positive = (x, y) => newRenderedImage.setColor(x, y, RGBColor.fromAwt(awt.Color.GREEN)),
          negative = (x, y) => newRenderedImage.setColor(x, y, RGBColor.fromAwt(awt.Color.RED))
        )

        renderedImage = newRenderedImage
      }.onError { throwable =>
        throwable.printStackTrace()
        IO.raiseError(throwable)
      }
    }.attempt.map(println).compile.drain.unsafeRunAndForget()(IORuntime.global)
    window.show()
  }
}
