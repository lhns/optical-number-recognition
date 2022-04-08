package onr

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.RGBColor
import fs2._
import onr.ImageMatcher.Stencil
import org.http4s.Uri
import org.http4s.jdkhttpclient.JdkHttpClient

import java.awt
import scala.concurrent.duration.DurationInt

object Main {
  def main(args: Array[String]): Unit = {
    val cam = EspCam(Uri.unsafeFromString("http://10.1.15.21"), None)

    val (numberwheel, image) = (for {
      numberwheelFiber <- IO.defer {
        Stencil.prepare(
          ImmutableImage.loader().fromBytes(
            getClass.getClassLoader.getResourceAsStream("numberwheel.png").readAllBytes()
          ),
          numItems = 11,
          border = 20,
        )
      }.start

      image <- JdkHttpClient.simple[IO].use { client =>
        cam.takePicture(client)
      }.map { bytes =>
        ImageCleanup.cleanup(
          ImmutableImage.loader().fromBytes(bytes),
          thesholdFactor = 0.7
        ).max(800, 600)
      }

      numberwheel <- numberwheelFiber.joinWithNever
    } yield
      (numberwheel, image))
      .unsafeRunSync()(IORuntime.global)

    @volatile
    var renderedImage = image

    var digits: Seq[(Int, Double)] = Seq.empty

    def digitsToNumber(digits: Seq[(Int, Double)]): Long = {
      val orderedDigits = digits.sortBy(_._1).map(_._2)
      orderedDigits.foldRight("") {
        case (digit, string) =>
          val roundedDigit: Int = Math.round(digit).toInt
          roundedDigit + string
      }.toLong
    }

    val window = CanvasWindow("Test", 10, 10, Stream.every[IO](1.seconds).evalMap(_ => IO {
      renderedImage
    }))

    val charHeight = 75 //67
    var ignored: Set[(Int, Int)] = Set.empty

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
        val charWidth = numberwheel.itemWidth(charHeight)

        val ex = event.getX.toInt - (charWidth + 1) / 2
        val ey = event.getY.toInt

        val (_, num, x, y) = ImageMatcher.findBestScore(
          image, ex, ey,
          stencilAt = num => numberwheel.itemImage(charHeight, num)
        ).unsafeRunSync()(IORuntime.global)

        println(num)
        digits = digits :+ ((x, num))
        println(digitsToNumber(digits))

        val newRenderedImage = renderedImage.overlay(
          numberwheel.itemImage(charHeight, num),
          x, y
        )

        ImageMatcher.matchLoop(image, x, y,
          stencil = numberwheel.itemImage(charHeight, num),
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
