package onr

import onr.color._

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

trait Image {
  def width: Int

  def height: Int

  def pixel(x: Int, y: Int): Color

  def withPixel(x: Int, y: Int, color: Color): Image

  def mutable: MutableImage

  def rect(newX: Int, newY: Int, newWidth: Int, newHeight: Int): Image = new Image {
    override def width: Color = newWidth

    override def height: Color = newHeight

    override def pixel(x: Color, y: Color): Color = Image.this.pixel(x + newX, y + newY)

    override def withPixel(x: Color, y: Color, color: Color): Image = {
      val mutableImage = mutable
      mutableImage.setPixel(x, y, color)
      mutableImage
    }

    override def mutable: MutableImage = {
      val mutableImage = Image.blankMutable(newWidth, newHeight, Color.Black)
      for {
        y <- 0 until newHeight
        x <- 0 until newWidth
      } mutableImage.setPixel(x, y, pixel(x, y))
      mutableImage
    }
  }

  def toBufferedImage: BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    for {
      y <- 0 until height
      x <- 0 until width
    } {
      image.setRGB(x, y, pixel(x, y))
    }

    image
  }
}

trait MutableImage extends Image {
  def immutable: Image = mutable

  def setPixel(x: Int, y: Int, color: Color): Unit

  def setPixels(xOffset: Int, yOffset: Int, image: Image): Unit = {
    for {
      y <- 0 until image.height
      x <- 0 until image.width
    } if (x + xOffset < width && y + yOffset < height)
      setPixel(x + xOffset, y + yOffset, image.pixel(x, y))
  }
}

object Image {
  private def pixelIndex(x: Int, y: Int, width: Int): Int = x + y * width

  case class ImageImpl(width: Int, height: Int, private val pixels: Array[Color]) extends MutableImage {
    require(pixels.length >= width * height)

    def pixel(x: Int, y: Int): Color = pixels(Image.pixelIndex(x, y, width))

    private def withPixels(pixels: Array[Color]): Image = copy(pixels = pixels)

    def withPixel(x: Int, y: Int, color: Color): Image = withPixels(pixels.updated(Image.pixelIndex(x, y, width), color))

    override def setPixel(x: Color, y: Color, color: Color): Unit = pixels(Image.pixelIndex(x, y, width)) = color

    override def mutable: MutableImage = copy(pixels = pixels.clone())
  }

  def blankMutable(width: Int, height: Int, color: Color): MutableImage = {
    val pixels = Array.fill(pixelIndex(0, height, width))(color)
    ImageImpl(width, height, pixels)
  }

  def blank(width: Int, height: Int, color: Color): Image =
    blankMutable(width, height, color)

  def fromBufferedImage(bufferedImage: BufferedImage): Image = {
    val width = bufferedImage.getWidth
    val height = bufferedImage.getHeight

    ImageImpl(width, height,
      (for {
        y <- 0 until height
        x <- 0 until width
      } yield
        Color.fromARGB(bufferedImage.getRGB(x, y))).toArray
    )
  }

  def read(path: Path): Image = {
    val inputStream = Files.newInputStream(path)
    val bufferedImage = ImageIO.read(inputStream)
    if (bufferedImage == null) inputStream.close()
    fromBufferedImage(bufferedImage)
  }

  def read(bytes: Array[Byte]): Image = {
    val inputStream = new ByteArrayInputStream(bytes)
    fromBufferedImage(ImageIO.read(inputStream))
  }
}
