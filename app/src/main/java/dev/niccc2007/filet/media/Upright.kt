package dev.niccc2007.filet.media

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * This picture the way up its JPEG orientation says it is meant to be seen. See [JpegOrientation].
 *
 * Returns itself when there is nothing to do. Otherwise the turned copy, with this one recycled -
 * so the caller must not hold on to the bitmap it passed in.
 */
internal fun Bitmap.upright(orientation: Int): Bitmap {
    val t = JpegOrientation.transformOf(orientation)
    if (!t.mirrored && t.degreesClockwise == 0) return this
    // Mirror first, then turn: the order the orientation values are defined in. The other order
    // gives the right answer for six of the eight values and swaps the two diagonal mirrors.
    val m = Matrix()
    if (t.mirrored) m.postScale(-1f, 1f)
    if (t.degreesClockwise != 0) m.postRotate(t.degreesClockwise.toFloat())
    val out = Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    if (out !== this) recycle()
    return out
}

/** This picture turned [quarters] quarter turns clockwise, on the same terms as [upright]. */
internal fun Bitmap.turnedBy(quarters: Int): Bitmap =
    upright(JpegOrientation.turned(JpegOrientation.NORMAL, quarters))
