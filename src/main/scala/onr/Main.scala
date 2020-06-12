package onr

import java.nio.file.Paths

import fs2._
import monix.eval.Task

import scala.concurrent.duration._

object Main {
  def main(args: Array[String]): Unit = {
    val image = Image.read(Paths.get("D:\\pierr\\Downloads\\stromzaehler.png"))
    val image2 = Image.read(Paths.get("D:\\pierr\\Downloads\\müllhalde2.jpg"))
    val imageStream = (
      Stream(image) ++
        Stream.sleep_[Task](2.seconds) ++
        Stream(image2) ++
        Stream.sleep_[Task](2.seconds)
      ).repeat

    CanvasWindow("Test", 10, 10, imageStream).show()
  }
}
