package dev.niccc2007.filet.handlers

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import dev.niccc2007.filet.docs.SvgSize
import dev.niccc2007.filet.vfs.types.FileTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * SVG drawings in the picture viewer.
 *
 * Bug identified here: the viewer decodes with `BitmapFactory`, which reads pixel formats only.
 * An SVG is a drawing described in XML, so the decode came back null and the viewer said "This
 * is not an image Android can decode" - for a file the type table lists as a picture, which is
 * why the viewer was the one chosen to open it.
 *
 * An SVG is drawn once, into a bitmap, with AndroidSVG - already in the app behind Coil's SVG
 * decoder, which the document reader uses. It is drawn with its long side at [EDGE] rather than
 * at the size it declares: a 24-pixel icon drawn at 24 pixels is a blur the moment it is zoomed,
 * and a vector drawn larger loses nothing. From there it is a bitmap like any other, so zoom, the
 * editor, turning and Save as new all work unchanged.
 *
 * Saving over the .svg is never offered. An edit is pixels and the file is a drawing; the type
 * table marks SVG lossless, so an edited or turned copy is written as a PNG, which keeps the
 * transparency most SVGs have.
 */
internal object SvgPicture {

    /** The long side it is drawn at: the ceiling the viewer decodes photos to. */
    const val EDGE = 2400

    /** Past this the file is not read: AndroidSVG holds the whole document as a tree. */
    const val MAX_BYTES = 24L * 1024 * 1024

    /** Enough of the file to hold its root tag, past an XML declaration, a doctype and comments. */
    private const val HEAD_BYTES = 16 * 1024

    /** Whether [extension] is drawn by this rather than decoded. */
    fun handles(extension: String): Boolean = FileTypes.of(extension)?.vector == true

    /**
     * The pixel size to draw at: [declared] (width and height in CSS pixels, or null when the file
     * says nothing usable) scaled so its long side is [maxEdge]. Square when there is nothing to
     * keep the proportions of.
     */
    fun rasterSize(declared: Pair<Float, Float>?, maxEdge: Int): Pair<Int, Int> {
        if (declared == null || declared.first <= 0f || declared.second <= 0f) return maxEdge to maxEdge
        val (w, h) = declared
        val scale = maxEdge / maxOf(w, h)
        return (w * scale).roundToInt().coerceIn(1, maxEdge) to (h * scale).roundToInt().coerceIn(1, maxEdge)
    }

    /** All of [input], or null when it runs past [MAX_BYTES]. A size the listing gave is not trusted. */
    fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
    }

    /**
     * [bytes] drawn into a new ARGB_8888 bitmap whose long side is [maxEdge], or null when
     * AndroidSVG cannot read it. ARGB_8888 because the editor reads the pixels back, and a
     * hardware bitmap has none to read.
     */
    fun render(bytes: ByteArray, maxEdge: Int = EDGE): Bitmap? {
        val svg = runCatching { SVG.getFromInputStream(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val declared = SvgSize.declared(String(bytes, 0, minOf(bytes.size, HEAD_BYTES), Charsets.UTF_8))
        val (w, h) = rasterSize(declared, maxEdge)
        // Without a viewBox, AndroidSVG draws at the declared size and does not scale it to the
        // viewport, so the drawing would sit in one corner of the bitmap at its icon size. A
        // viewBox the size it declares makes it scale; width and height at 100% make it fill.
        if (svg.documentViewBox == null && declared != null) svg.setDocumentViewBox(0f, 0f, declared.first, declared.second)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return runCatching {
            svg.renderToCanvas(Canvas(bitmap), RenderOptions.create().viewPort(0f, 0f, w.toFloat(), h.toFloat()))
            bitmap
        }.getOrElse {
            bitmap.recycle()
            null
        }
    }
}
