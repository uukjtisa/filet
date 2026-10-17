package dev.niccc2007.filet.docs

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * A PDF, as pages that can be asked for one at a time.
 *
 * ## Why there is no dependency here
 *
 * `android.graphics.pdf.PdfRenderer` has been in Android since API 21 and is in every device
 * Filet runs on. It is a real PDF engine - the same one the system's own print preview uses -
 * and it costs nothing in the APK.
 *
 * The modern alternative, `androidx.pdf`, is a complete viewer with text selection and search,
 * and it was rejected: it requires **API 31**, and Filet's floor is Android 8. Taking it would
 * mean either raising the floor by five versions or shipping two PDF viewers, and a phone on
 * Android 8 is exactly the phone whose owner is most likely to be reading a PDF in a file
 * manager rather than in an office suite.
 *
 * ## Why pages are rendered on demand and only a few are kept
 *
 * A page rendered at a readable width is several megabytes as a bitmap. A hundred-page document
 * held in full would be most of a phone's heap, so pages are rendered as they are scrolled to
 * and [KEEP] of them are kept either side. That is also why this is a class with a lifetime
 * rather than a function: `PdfRenderer` holds a file descriptor and leaking one leaks the file.
 *
 * ## Not editable, and that is not a gap to be filled later
 *
 * `PdfRenderer` renders; it cannot write. Editing a PDF means a layout engine, a font
 * subsetter and an incremental-update writer - a product, not a feature - and the honest thing
 * is to not offer a save button rather than to offer one that flattens the document.
 */
class PdfDoc private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : AutoCloseable {

    val pages: Int get() = runCatching { renderer.pageCount }.getOrDefault(0)

    /**
     * Render one page to a bitmap [width] pixels across.
     *
     * The height follows the page's own aspect, so nothing is stretched. Null rather than an
     * exception for a page that will not render: a single corrupt page in a long document
     * should be a gap in the scroll, not the end of reading it.
     */
    fun render(index: Int, width: Int): Bitmap? = synchronized(this) {
        if (index < 0 || index >= pages || width <= 0) return null
        runCatching {
            renderer.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(
                    width.coerceAtMost(MAX_WIDTH),
                    height.coerceAtMost(MAX_HEIGHT),
                    Bitmap.Config.ARGB_8888,
                )
                // WHITE first. PdfRenderer draws only what the page paints and leaves the
                // rest of the bitmap at whatever it was - which for a fresh ARGB_8888 is
                // transparent black, so an ordinary page of text renders as white text on
                // black. Every report of a PDF viewer showing "inverted" pages is this.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    /** The shape of a page, for laying out a placeholder before it has been rendered. */
    fun aspect(index: Int): Float = synchronized(this) {
        if (index < 0 || index >= pages) return DEFAULT_ASPECT
        runCatching {
            renderer.openPage(index).use { page ->
                if (page.width <= 0) DEFAULT_ASPECT else page.height.toFloat() / page.width
            }
        }.getOrDefault(DEFAULT_ASPECT)
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {

        /** A4 at portrait, which is what an unrendered page is assumed to be. */
        const val DEFAULT_ASPECT = 1.414f

        /** How many rendered pages to keep either side of the one being read. */
        const val KEEP = 2

        /**
         * The largest bitmap a page is rendered into.
         *
         * Four thousand across. Past a phone's own pixel width there is nothing to see, and a
         * bitmap bigger than this is both slow to produce and a real risk of an allocation
         * failure on a device with a small heap - which would take the app down over a
         * document somebody only wanted to look at.
         */
        const val MAX_WIDTH = 4_000
        const val MAX_HEIGHT = 8_000

        /**
         * Open a PDF that is a real file on this filesystem.
         *
         * `PdfRenderer` needs a seekable file descriptor, which rules out a stream from a
         * provider - an archive entry, a remote share, a `content://` URI from another app.
         * Those have to be staged to local storage first, which the caller does, because
         * staging is a job with a progress bar and this is not the place for one.
         */
        /**
         * Open whatever [node] is, copying it out of an archive or off a share first.
         *
         * Here rather than in the screen because this file is the one allowed to hold a
         * `java.io.File` (see `check-r3.mjs`) - and a screen that took one would move the
         * handle a layer up, which is the thing that rule is actually about.
         */
        suspend fun open(
            copy: dev.niccc2007.filet.ops.LocalCopy,
            node: dev.niccc2007.filet.vfs.VNode,
        ): PdfDoc? {
            val file = copy.of(node) ?: return null
            return open(file)
        }

        fun open(file: File): PdfDoc? = runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = runCatching { PdfRenderer(fd) }.getOrElse { e ->
                runCatching { fd.close() }
                throw e
            }
            PdfDoc(fd, renderer)
        }.getOrNull()
    }
}
