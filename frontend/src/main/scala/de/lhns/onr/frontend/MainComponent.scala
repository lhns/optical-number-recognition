package de.lhns.onr.frontend

import cats.effect.IO
import cats.syntax.traverse._
import de.lhns.onr.Api.ImageJpg
import de.lhns.onr.Parameters.{Rect, Vec2}
import de.lhns.onr.{Api, Parameters}
import japgolly.scalajs.react.ScalaComponent.BackendScope
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ReactMouseEventFromHtml, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.{CanvasRenderingContext2D, HTMLCanvasElement, HTMLImageElement, HTMLInputElement}

import java.util.Base64
import scala.concurrent.duration._
import scala.scalajs.js

object MainComponent {
  case class Props()

  case class State(
                    lastFetch: Long,
                    parameters: Option[Parameters],
                    cursor: (Int, Int),
                    image: Option[HTMLImageElement],
                    preview: Option[(HTMLImageElement, Long)],
                  ) {
    def isFresh: Boolean = System.currentTimeMillis() - lastFetch < 10000
  }

  object State {
    val empty: State = State(0, None, (0, 0), None, None)
  }

  private def imageFromJpg(jpg: ImageJpg): IO[HTMLImageElement] = {
    val img = dom.document.createElement("img").asInstanceOf[HTMLImageElement]
    img.src = "data:image/png;base64," + Base64.getEncoder.encodeToString(jpg.bytes)
    IO.async_[HTMLImageElement](callback => img.onload = _ => callback(Right(img)))
  }

  implicit class RenderingContextOps(val ctx: CanvasRenderingContext2D) extends AnyVal {
    def drawLine(color: String, lineWidth: Double, x: Int, y: Int, xOff: Int, yOff: Int): Unit = {
      ctx.strokeStyle = color
      ctx.lineWidth = lineWidth
      ctx.beginPath()
      ctx.moveTo(x, y)
      ctx.lineTo(x + xOff, y + yOff)
      ctx.stroke()
    }

    def drawRect(color: String, lineWidth: Double, x: Int, y: Int, xOff: Int, yOff: Int): Unit = {
      ctx.strokeStyle = color
      ctx.lineWidth = lineWidth
      ctx.strokeRect(x, y, xOff, yOff)
    }
  }

  class Backend($: BackendScope[Props, State]) {
    private def fetchParameters: IO[Parameters] =
      for {
        parameters <- Api.getParameters(())
        _ <- $.modStateAsync(_.copy(parameters = Some(parameters), lastFetch = System.currentTimeMillis()))
      } yield
        parameters

    private def updateParameters(parameters: Parameters, modState: State => State = identity): IO[Unit] =
      for {
        _ <- $.modStateAsync(e => modState(e.copy(parameters = Some(parameters))))
        _ <- Api.setParameters(parameters).start
      } yield ()

    private def fetchState: IO[Unit] =
      for {
        state <- $.state.to[IO]
        _ <- if (state.isFresh) IO(state.parameters) else fetchParameters
        image <- Api.previewImage(()).flatMap(imageFromJpg)
        _ <- $.modStateAsync(_.copy(image = Some(image)))
      } yield ()

    def componentDidMount: IO[Unit] = {
      lazy val tick: IO[Unit] =
        fetchState >>
          tick.delayBy(20.seconds)

      tick
    }

    def render(state: State): VdomElement = {
      <.div(^.height := "100vh", ^.className := "container d-flex flex-column",
        state.parameters.fold {
          Seq.empty[VdomElement].toVdomArray
        } { parameters =>
          Seq(
            <.div(
              ^.className := "d-flex flex-column gap-3",
              <.h1(
                "Optical Number Recognition Settings"
              ),
              <.div(
                ^.className := "d-flex flex-row align-items-center justify-content-end gap-3",
                <.input(
                  ^.tpe := "range",
                  ^.className := "form-range flex-fill",
                  ^.min := 0,
                  ^.max := 400,
                  ^.value := parameters.height,
                  ^.onChange ==> { e: ReactEventFromInput =>
                    val value = e.target.valueAsNumber
                    updateParameters(parameters.copy(height = value.toInt))
                  }
                ),
                <.input(
                  ^.key := "input",
                  ^.className := "form-control",
                  ^.width := "5em",
                  ^.placeholder := "height",
                  ^.value := parameters.height,
                  ^.untypedRef {
                    case input: HTMLInputElement =>
                      input.selectionStart = state.cursor._1
                      input.selectionEnd = state.cursor._2
                      println("rendered")
                    case _ =>
                  },
                  ^.onChange ==> { (e: ReactEventFromInput) =>
                    val value = e.target.value
                    val cursor = (e.target.selectionStart, e.target.selectionEnd)
                    val newParameters = parameters.copy(height = value.toInt)
                    updateParameters(newParameters, modState = _.copy(cursor = cursor))
                  }
                ),
              ),
              <.div(
                ^.className := "d-flex flex-row align-items-center justify-content-end gap-3",
                state.preview.map {
                  case (_, number) =>
                    <.h4(
                      "Detected: " + number
                    )
                },
                <.div(^.className := "flex-fill"),
                <.button(
                  ^.className := "btn btn-danger",
                  "Delete Ignore",
                  ^.onClick --> IO.defer {
                    updateParameters(parameters.copy(ignored = parameters.ignored.drop(1)))
                  }
                ),
                <.button(
                  ^.className := "btn btn-primary",
                  "Add Ignore",
                  ^.onClick --> IO.defer {
                    updateParameters(parameters.copy(ignored = Rect(0, 0, 10, 10) +: parameters.ignored))
                  }
                ),
                <.button(
                  ^.className := "btn btn-danger",
                  "Delete Cursor",
                  ^.onClick --> IO.defer {
                    updateParameters(parameters.copy(vectors = parameters.vectors.drop(1)))
                  }
                ),
                <.button(
                  ^.className := "btn btn-primary",
                  "Add Cursor",
                  ^.onClick --> IO.defer {
                    updateParameters(parameters.copy(vectors = Vec2(0, 0) +: parameters.vectors))
                  }
                ),
                if (state.preview.isEmpty) {
                  <.button(
                    ^.className := "btn btn-primary",
                    "Detect",
                    ^.onClick --> IO.defer {
                      for {
                        jpg <- Api.previewDetected(())
                        number <- Api.detected(())
                        image <- imageFromJpg(jpg)
                        _ <- $.modStateAsync(_.copy(preview = Some((image, number))))
                      } yield ()
                    }
                  )
                } else {
                  <.button(
                    ^.className := "btn btn-primary",
                    "Camera",
                    ^.onClick --> IO.defer {
                      $.modStateAsync(_.copy(preview = None))
                    }
                  )
                },
              ),
            ),
            <.hr(),
            <.div(^.className := "flex-fill",
              state.preview.map(_._1).orElse(state.image).map { image =>
                <.canvas(
                  ^.id := "canvas",
                  ^.untypedRef {
                    case canvas: HTMLCanvasElement =>
                      canvas.width = image.width
                      canvas.height = image.height
                      val ctx = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
                      ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
                      parameters.vectors.foreach { vector =>
                        val lineWidth = 3
                        val cursorWidth = 20
                        val color = "#f0dc26"
                        val y = vector.y - (parameters.height + 1) / 2
                        ctx.drawLine(color, lineWidth, vector.x, y, 0, parameters.height)
                        ctx.drawLine(color, lineWidth, vector.x - cursorWidth / 2, y, cursorWidth, 0)
                        ctx.drawLine(color, lineWidth, vector.x - cursorWidth / 2, y + parameters.height, cursorWidth, 0)
                      }
                      parameters.ignored.foreach { rect =>
                        val lineWidth = 3
                        val color = "#0000ff"
                        ctx.drawRect(color, lineWidth, rect.x, rect.y, rect.width, rect.height)
                        val colorHandle = "#00ff00"
                        val handleSize = 10
                        ctx.drawLine(colorHandle, lineWidth, rect.x, rect.y, -handleSize, 0)
                        ctx.drawLine(colorHandle, lineWidth, rect.x, rect.y, 0, -handleSize)
                        ctx.drawLine(colorHandle, lineWidth, rect.x + rect.width, rect.y + rect.height, handleSize, 0)
                        ctx.drawLine(colorHandle, lineWidth, rect.x + rect.width, rect.y + rect.height, 0, handleSize)
                      }
                    case _ =>
                  },
                  ^.onMouseDown ==> ((e: ReactMouseEventFromHtml) => IO.pure {
                    e.target.asInstanceOf[js.Dynamic].mousedown = true
                  }),
                  ^.onMouseUp ==> ((e: ReactMouseEventFromHtml) => IO.pure {
                    e.target.asInstanceOf[js.Dynamic].mousedown = false
                  }),
                  ^.onMouseMove ==> { (e: ReactMouseEventFromHtml) =>
                    IO.defer {
                      if (e.target.asInstanceOf[js.Dynamic].mousedown.asInstanceOf[Boolean] == true) {
                        val x = (e.clientX - e.target.getBoundingClientRect().left).toInt
                        val y = (e.clientY - e.target.getBoundingClientRect().top).toInt
                        val maxDist = 30
                        trait Elem {
                          def x: Int

                          def y: Int

                          def dist2(x: Int, y: Int): Int

                          def moveTo(x: Int, y: Int, parameters: Parameters): Parameters
                        }
                        val elems = {
                          parameters.vectors.map { vec =>
                            new Elem {
                              override def x: Int = vec.x

                              override def y: Int = vec.y

                              override def dist2(x: Int, y: Int): Int = vec.dist2(x, y)

                              override def moveTo(x: Int, y: Int, parameters: Parameters): Parameters = {
                                val otherVectors = {
                                  val (matching, nonMatching) = parameters.vectors.partition(_ == vec)
                                  matching.drop(1) ++ nonMatching
                                }
                                parameters.copy(vectors = Vec2(x, y) +: otherVectors)
                              }
                            }
                          } ++ parameters.ignored.flatMap { rect =>
                            Seq(
                              new Elem {
                                override def x: Int = rect.topLeftHandle.x

                                override def y: Int = rect.topLeftHandle.y

                                override def dist2(x: Int, y: Int): Int = rect.topLeftHandle.dist2(x, y)

                                override def moveTo(x: Int, y: Int, parameters: Parameters): Parameters = {
                                  val otherRects = {
                                    val (matching, nonMatching) = parameters.ignored.partition(_ == rect)
                                    matching.drop(1) ++ nonMatching
                                  }
                                  parameters.copy(ignored = rect.moveTopLeftHandle(x, y) +: otherRects)
                                }
                              },
                              new Elem {
                                override def x: Int = rect.bottomRightHandle.x

                                override def y: Int = rect.bottomRightHandle.y

                                override def dist2(x: Int, y: Int): Int = rect.bottomRightHandle.dist2(x, y)

                                override def moveTo(x: Int, y: Int, parameters: Parameters): Parameters = {
                                  val otherRects = {
                                    val (matching, nonMatching) = parameters.ignored.partition(_ == rect)
                                    matching.drop(1) ++ nonMatching
                                  }
                                  parameters.copy(ignored = rect.moveBottomRightHandle(x, y) +: otherRects)
                                }
                              }
                            )
                          }
                        }
                        elems
                          .minByOption(_.dist2(x, y))
                          .filterNot(e => e.x == x && e.y == y)
                          .filter(e => e.dist2(x, y) < maxDist * maxDist)
                          .map { e =>
                            updateParameters(e.moveTo(x, y, parameters))
                          }.sequence.void
                      } else
                        IO.unit
                    }
                  }
                )
              }
              //<.img(^.src := "/api/preview/image")
            ),
          ).toVdomArray
        }
      )
    }
  }

  val Component =
    ScalaComponent.builder[Props]
      .initialState(State.empty)
      .backend(new Backend(_))
      .render(e => e.backend.render(e.state))
      .componentDidMount(_.backend.componentDidMount)
      .build
}
