package onr

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

object Converter {
  def main(args: Array[String]): Unit = {
    val dir = Paths.get("D:\\pierr\\Documents\\git\\optical-number-recognition\\src\\main\\resources")
    Files.list(dir).iterator().asScala.filter(e => e.getFileName.toString.matches(".*\\.jpe?g")).foreach { file =>
      val outDir = dir.resolve("bw")
      //Files.createDirectory(outDir)
      ImageCleanup.cleanup(ImmutableImage.loader().fromPath(file), 0.7).output(PngWriter.MinCompression, outDir.resolve(file.getFileName))
    }
  }
}
