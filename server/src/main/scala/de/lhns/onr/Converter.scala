package de.lhns.onr

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

object Converter {
  def main(args: Array[String]): Unit = {
    val inputDir = Paths.get(args(0))
    val outputDir = Paths.get(args(1))
    Files.list(inputDir)
      .iterator.asScala
      .filter(e => e.getFileName.toString.matches(".*\\.jpe?g")).foreach { file =>
      //ImageCleanup.cleanup(
      ImmutableImage.loader().fromPath(file)
        //thesholdFactor = 0.7
        .output(
          PngWriter.MinCompression,
          outputDir.resolve(file.getFileName)
        )
    }
  }
}
