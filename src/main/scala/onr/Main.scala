package onr

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.RGBColor
import com.sksamuel.scrimage.filter.BlurFilter
import com.sksamuel.scrimage.pixels.Pixel
import fs2._
import thirdparty.jhlabs.image.PixelUtils

import java.awt
import java.nio.file.Paths
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

    val numberwheel = ImmutableImage.loader().fromPath(dir.resolve("numberwheel.png"))
      .takeLeft(180)
      .bound(28, 1000)
      .pipe { image =>
        image
          .filter(new BlurFilter())
          .filter(new BlurFilter())
          .filter(new BlurFilter())
          .filter(new BlurFilter())
        //.filter(new ThresholdFilter(127, awt.Color.BLACK.getRGB, awt.Color.WHITE.getRGB))
      }

    def charStencil(offset: Double): ImmutableImage = {
      val charHeight = numberwheel.height / 11
      numberwheel.trimTop((offset * charHeight).toInt).pipe(img => img.takeTop(Math.min(img.height, (charHeight * 1.2).toInt)))
    }


    val image = ImageCleanup.cleanup(ImmutableImage.loader().fromPath(path4))
      //.trim(20, 600, 20, 800)
      .trim(350, 250, 350, 100)
      .max(1200, 1000)

    def isBlack(pixel: Pixel): Boolean = pixel.argb == 0xFF << 24

    def brightness(pixel: Pixel): Int = 255 - ((255 - PixelUtils.brightness(pixel.argb)) * pixel.alpha() / 255)

    def matchLoop(image: ImmutableImage, x: Int, y: Int, stencil: ImmutableImage, positive: (Int, Int) => Unit, negative: (Int, Int) => Unit): Unit = {
      for {
        yOff <- 0 until stencil.height
        xOff <- 0 until stencil.width
      } {
        val imagePixelBrightness = brightness(image.pixel(x + xOff, y + yOff))
        val stencilPixelBrightness = brightness(stencil.pixel(xOff, yOff))

        if (stencilPixelBrightness < 50) {
          if (imagePixelBrightness == 0) {
            positive(x + xOff, y + yOff)
          } else {
            negative(x + xOff, y + yOff)
          }
        } else if (stencilPixelBrightness > 160 && stencilPixelBrightness < 255 && imagePixelBrightness == 0) {
          negative(x + xOff, y + yOff)
        }
      }
    }

    def findBestScore(image: ImmutableImage, x: Int, y: Int): (Int, Double, Int, Int) = {
      IO.parSequenceN(16)((for {
        numX10 <- 0 until 110
        num = numX10 / 10.0
        stencil = charStencil(num)
        yOff <- -8 to 8
        xOff <- -8 to 8
      } yield IO {
        var score: Long = 0
        var fails: Set[(Int, Int)] = Set.empty
        matchLoop(image, x + xOff, y + yOff, stencil,
          positive = (_, _) => score += 1,
          negative = (x, y) => {
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
        (score.toInt, num % 10, x + xOff, y + yOff)
      }).toList).unsafeRunSync()(IORuntime.global).maxBy(_._1)
    }

    @volatile
    var renderedImage = image

    val window = CanvasWindow("Test", 10, 10, Stream.repeatEval(IO {
      renderedImage
    }))
    window.onClickStream.evalMap { event =>
      IO {
        val image = renderedImage
        println(event)
        val ex = event.getX.toInt
        val ey = event.getY.toInt

        val (_, num, x, y) = findBestScore(image, ex, ey)
        println(Math.round(num))

        val numbers = charStencil(num)
        val newRenderedImage = image.overlay(numbers, x, y)

        matchLoop(image, x, y, charStencil(num),
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
