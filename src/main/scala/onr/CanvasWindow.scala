package onr

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime
import com.sksamuel.scrimage.MutableImage
import fs2._
import javafx.embed.swing.SwingFXUtils
import javafx.scene.input.MouseEvent
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.scene.Scene
import scalafx.scene.canvas.{Canvas, GraphicsContext}

case class CanvasWindow(title: String,
                        width: Double,
                        height: Double,
                        images: Stream[IO, MutableImage]) {
  private val onClickQueue = Queue.circularBuffer[IO, MouseEvent](10).unsafeRunSync()(IORuntime.global)

  def onClickStream: Stream[IO, MouseEvent] = Stream.fromQueueUnterminated(onClickQueue)

  def show(): Unit =
    new JFXApp {
      private var canvas: Canvas = _

      stage = new PrimaryStage {
        title.value = CanvasWindow.this.title

        scene = new Scene(CanvasWindow.this.width, CanvasWindow.this.height) {
          canvas = new Canvas(CanvasWindow.this.width, CanvasWindow.this.height) {
            resizable = true
          }

          content = canvas

          canvas.onMouseClicked = (event: MouseEvent) => onClickQueue.offer(event).unsafeRunSync()(IORuntime.global)
        }
      }

      private val graphics2d: GraphicsContext = canvas.graphicsContext2D

      images.evalMap { image =>
        IO.blocking {
          val fxImage = SwingFXUtils.toFXImage(image.awt(), null)
          stage.setWidth(image.width + 25)
          stage.setHeight(image.height + 30)
          canvas.setWidth(image.width)
          canvas.setHeight(image.height)
          graphics2d.drawImage(fxImage, 0, 0)
        }.onError { throwable =>
          throwable.printStackTrace()
          IO.raiseError(throwable)
        }
      }.compile.drain.unsafeRunAndForget()(IORuntime.global)
    }.main(Array[String]())
}
