package onr

import java.awt

package object color {
  type Color = Int

  object Color {
    def apply(r: Int, g: Int, b: Int, a: Int): Color = {
      ((a & 0xFF) << 24) |
        ((r & 0xFF) << 16) |
        ((g & 0xFF) << 8) |
        (b & 0xFF)
    }

    def apply(r: Int, g: Int, b: Int): Color = Color(r, g, b, 255)

    def fromARGB(color: Int): Color = color

    def fromRGBA(color: Int): Color = ((color >>> 8) & 0xFFFFFF) | ((color & 0xFF) << 24)

    def fromRGB(color: Int): Color = (color & 0xFFFFFF) | (0xFF << 24)

    def fromAwt(color: awt.Color): Color = color.getRGB


    val Black: Color = Color(0, 0, 0)

    val White: Color = Color(255, 255, 255)

    val Red: Color = Color(255, 0, 0)

    val Green: Color = Color(0, 255, 0)

    val Blue: Color = Color(0, 0, 255)
  }

  implicit class ColorOps(val argb: Color) extends AnyVal {
    def a: Int = (argb >>> 24) & 0xFF

    def r: Int = (argb >>> 16) & 0xFF

    def g: Int = (argb >>> 8) & 0xFF

    def b: Int = argb & 0xFF


    def withA(a: Int): Color = (argb & ~(0xFF << 24)) | ((a & 0xFF) << 24)

    def withR(r: Int): Color = (argb & ~(0xFF << 16)) | ((r & 0xFF) << 16)

    def withG(g: Int): Color = (argb & ~(0xFF << 8)) | ((g & 0xFF) << 8)

    def withB(b: Int): Color = (argb & ~0xFF) | (b & 0xFF)


    def tupled: (Int, Int, Int, Int) = (r, g, b, a)

    def toAwt: awt.Color = new awt.Color(r, g, b)
  }

}
