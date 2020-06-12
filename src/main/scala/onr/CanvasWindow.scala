package onr

import fs2._
import fs2.concurrent.Queue
import javafx.embed.swing.SwingFXUtils
import javafx.scene.input.MouseEvent
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import scalafx.application.JFXApp
import scalafx.application.JFXApp.PrimaryStage
import scalafx.scene.Scene
import scalafx.scene.canvas.{Canvas, GraphicsContext}

case class CanvasWindow(title: String,
                        width: Double,
                        height: Double,
                        images: Stream[Task, Image]) {
  private val onClickQueue = Queue.bounded[Task, MouseEvent](10).runSyncUnsafe()(Scheduler.global, CanBlock.permit)

  def onClickStream: Stream[Task, MouseEvent] = onClickQueue.dequeue

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

          canvas.onMouseClicked = (event: MouseEvent) => onClickQueue.enqueue1(event).runAsyncAndForget(Scheduler.global)
        }
      }

      private val graphics2d: GraphicsContext = canvas.graphicsContext2D

      images.map { image =>
        val fxImage = SwingFXUtils.toFXImage(image.toBufferedImage, null)
        stage.setWidth(image.width)
        stage.setHeight(image.height)
        canvas.setWidth(image.width)
        canvas.setHeight(image.height)
        graphics2d.drawImage(fxImage, 0, 0)

      }.compile.drain.runToFuture(Scheduler.singleThread("render-images"))
    }.main(Array[String]())
}
