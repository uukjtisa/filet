package dev.niccc2007.filet.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Finding a QR code in a picture or a camera frame, on zxing's core - already in the app for the
 * share code, so reading costs nothing to add. No Play Services: the ML Kit scanner would pull in
 * a Google dependency an F-Droid build cannot ship, for one feature.
 *
 * This file is the Android half: getting the pixels. What is tried on them, and in what order,
 * is [QrLadder], which runs off the device and is tested there.
 */
object QrDecode {

    /**
     * The smallest a photo is loaded at, on its longest side, before the ladder shrinks it
     * further. Loaded by halving - which the JPEG decoder does nearly for free - and so somewhere
     * from this to twice it: a 4000px phone photo arrives at 2000. Big enough that a code a tenth
     * of the frame across still has several pixels a module for the first attempt.
     */
    private const val MIN_LOADED_SIDE = 1600

    /** Rows converted to brightness at a time, so a photo never exists as a full array of colour ints. */
    private const val BAND = 64

    /**
     * The text of the first code in a picture, or null. Blocking: call off the main thread.
     *
     * @param keepGoing asked between attempts. A viewer that has moved on stops the search.
     */
    fun fromStream(open: () -> InputStream, keepGoing: () -> Boolean = { true }): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MIN_LOADED_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { open().use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull() ?: return null
        val luma = try { lumaOf(bitmap) } finally { bitmap.recycle() }
        return QrLadder.read(luma, QrLadder.photo(luma.longest), thorough = true, keepGoing = keepGoing)
    }

    private fun lumaOf(bitmap: Bitmap): Luma {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h)
        val band = IntArray(w * minOf(BAND, h))
        var top = 0
        while (top < h) {
            val rows = minOf(BAND, h - top)
            bitmap.getPixels(band, 0, w, 0, top, w, rows)
            val at = top * w
            for (i in 0 until w * rows) out[at + i] = Luma.of(band[i])
            top += rows
        }
        return Luma(out, w, h)
    }

    /**
     * A camera frame's luminance plane, as CameraX hands it over. [frame] counts the frames, so
     * each one makes its share of the attempts - see [QrLadder.frame].
     */
    fun fromLuma(data: ByteArray, rowStride: Int, width: Int, height: Int, frame: Long, scratch: Luma.Scratch): String? {
        val luma = Luma(data, width, height, rowStride)
        return QrLadder.read(luma, QrLadder.frame(frame, luma.longest), thorough = false, scratch = scratch)
    }
}
