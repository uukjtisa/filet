package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.niccc2007.filet.media.JpegOrientation
import dev.niccc2007.filet.media.turnedBy
import dev.niccc2007.filet.media.upright
import dev.niccc2007.filet.vfs.types.FileTypes

/**
 * A picture saved the way the viewer has it turned.
 *
 * ## Why this is not the editor's save
 *
 * The editor works on a copy capped at 2400 pixels on its longest edge, because an edit holds
 * several full buffers at once. That is right for drawing on a photo and wrong for turning one:
 * a twelve megapixel photo saved through the editor comes back at a third of its resolution,
 * re-encoded, with its date, place and camera details gone - and all anybody asked for was
 * which way up it is.
 *
 * So a turn is saved from the FILE, not from what is on screen, and the format decides how:
 *
 * - **JPEG**: only the orientation tag changes ([JpegOrientation]). Lossless, every pixel and
 *   every detail of the file as it was, and undone by turning it back.
 * - **PNG**: decoded at full size, turned, and written again. PNG is lossless, so the picture is
 *   exactly the same; text chunks a PNG can carry are not kept.
 * - **Everything else** can only be saved as a new file. Android has no writer for HEIC, AVIF,
 *   GIF or BMP, and a WebP written back would be lossy where the original may not have been, or
 *   lose its animation. Replacing an original with something worse is not on offer.
 */
internal object PictureTurn {

    /** What saving over the original would do to it. */
    enum class Replace {
        /** The orientation tag changes and nothing else. */
        TAG,

        /** Written again at full size, without loss. */
        REWRITE,

        /** Not offered: this format can only be saved as a new file. */
        NONE,
    }

    fun replaceFor(extension: String): Replace {
        val e = FileTypes.norm(extension)
        return when {
            FileTypes.isJpeg(e) -> Replace.TAG
            e == "png" -> Replace.REWRITE
            else -> Replace.NONE
        }
    }

    /** What a new file would be saved as, for saying so before it happens. */
    fun newFormatFor(extension: String): String {
        val e = FileTypes.norm(extension)
        return when {
            FileTypes.isJpeg(e) || e == "png" -> e
            FileTypes.isLossless(e) -> "png"
            else -> "jpg"
        }
    }

    /**
     * @param orientation the tag the bytes now carry, when the turn was made by changing it, so
     *   the save can read it back and check.
     */
    class Turned(val bytes: ByteArray, val extension: String, val orientation: Int?)

    /**
     * [bytes] turned [quarters] quarter turns clockwise.
     *
     * @param replacing the result is going over the original, so it must be the format the
     *   original was, made the way [replaceFor] says - never a quiet fallback to something lossy.
     * @throws IllegalStateException with a sentence fit to show.
     */
    fun turn(bytes: ByteArray, extension: String, quarters: Int, replacing: Boolean): Turned {
        val ext = extension.lowercase()
        val way = replaceFor(ext)
        if (replacing && way == Replace.NONE) error("A ${ext.uppercase()} picture can only be saved turned as a new file.")

        if (way == Replace.TAG && JpegOrientation.isJpeg(bytes)) {
            val want = JpegOrientation.turned(JpegOrientation.read(bytes), quarters)
            JpegOrientation.withOrientation(bytes, want)?.let { return Turned(it, ext, want) }
            if (replacing) {
                error("This photo's details are laid out in a way Filet will not rewrite, so it was left as it was. It can still be saved as new.")
            }
        } else if (replacing && way == Replace.TAG) {
            error("This file is named as a JPEG and is not one, so it was left as it was.")
        }
        return reencoded(bytes, ext, quarters, replacing)
    }

    /** Decoded at full size, turned, encoded. See the class notes for when this is allowed to happen. */
    private fun reencoded(bytes: ByteArray, ext: String, quarters: Int, replacing: Boolean): Turned {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) error("This is not a picture Android can read.")
        // The source and the turned copy are alive at the same moment, at four bytes a pixel.
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        if (w.toLong() * h * 4 * 2 > free * 4 / 5) error("At $w x $h this picture is too large to turn at full size here.")

        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("This is not a picture Android can read.")
        // A JPEG that comes this way still has a tag saying how it was meant to be seen, and the
        // new file has none - so the tag's turn goes into the pixels first, then the viewer's.
        val orientation = if (FileTypes.isJpeg(ext)) JpegOrientation.read(bytes) else JpegOrientation.NORMAL
        val turned = decoded.upright(orientation).turnedBy(quarters)
        try {
            val encoded = encode(turned, ext)
            if (replacing && encoded.extension != ext) error("Filet cannot write this picture back as ${ext.uppercase()}.")
            return Turned(encoded.bytes, encoded.extension, null)
        } finally {
            turned.recycle()
        }
    }
}
