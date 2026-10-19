package dev.niccc2007.filet.docs

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * ## Two kinds of bitmap, and why
 *
 * A page is drawn twice over when zoomed. The **base** is the whole page at the width of the
 * screen: cheap enough to keep a handful of, and sharp at 1x. The **slice** is only the part of
 * a page that is on screen, rendered at the current zoom, laid over the base. Zooming the base
 * alone is what makes text go soft; re-rendering whole pages at the zoomed width is what runs a
 * small phone out of memory - a page at 4x is ninety megabytes. A slice is never larger than the
 * screen, whatever the zoom, so the sharp layer costs one screen of pixels and not one page per
 * zoom step. See [renderSlice] and [PdfPaging.slice].
 *
 * Bases are bounded by bytes, not by count - see [PdfPaging.plan] and [PdfPaging.budget].
 *
 * ## Thread safety
 *
 * `PdfRenderer` allows one open page at a time and is not safe across threads, and the screen
 * renders bases and slices on a background dispatcher while it is being closed from the main
 * thread. Every touch of [renderer] therefore holds this object's lock, [close] included.
 *
 * Bug identified here: `close` used to run without the lock, so leaving the viewer mid-render
 * closed the renderer under an open page. `PdfRenderer.close` throws in that state, the throw
 * was swallowed, and the renderer was left to its finaliser - a leaked descriptor per hasty
 * exit.
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

    /**
     * Read once, at open. `pageCount` is a field read, but asking for it from composition while
     * a render holds the renderer on another thread is a cross-thread touch of an object that
     * is documented as not supporting one.
     */
    val pages: Int = runCatching { renderer.pageCount }.getOrDefault(0)

    /**
     * Every page's height over its width, measured at open.
     *
     * Bug identified here: the aspect used to be read per list item, from composition, by
     * opening the page. Composition is the main thread and the call waited on this object's
     * lock - which a background render of a dense page holds for a few hundred milliseconds.
     * Scrolling a new page into view therefore stalled the frame behind whatever was being
     * rendered. Opening a page only to read its size costs well under a millisecond, so a
     * thousand-page file pays for all of them once, off the main thread, inside [open].
     */
    private val aspects: FloatArray = FloatArray(pages) { i ->
        runCatching {
            renderer.openPage(i).use { page ->
                if (page.width <= 0 || page.height <= 0) DEFAULT_ASPECT
                else page.height.toFloat() / page.width
            }
        }.getOrDefault(DEFAULT_ASPECT)
    }

    private var closed = false

    /** The shape of a page, for laying it out before it has been rendered. Never blocks. */
    fun aspect(index: Int): Float = if (index in aspects.indices) aspects[index] else DEFAULT_ASPECT

    /**
     * Render one whole page to a bitmap [width] pixels across.
     *
     * Null rather than an exception for a page that will not render: a single corrupt page in
     * a long document should be a gap in the scroll, not the end of reading it.
     */
    fun render(index: Int, width: Int): Bitmap? = synchronized(this) {
        if (closed || index < 0 || index >= pages || width <= 0) return null
        val size = PdfPaging.fit(width, aspect(index), MAX_WIDTH, MAX_HEIGHT)
        runCatching {
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(size.w, size.h, Bitmap.Config.ARGB_8888)
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

    /**
     * Render only the visible part of a page, at the zoom it is being looked at.
     *
     * [pageWidth] is the width in pixels the WHOLE page would have at this zoom; [slice] says
     * which part of it is wanted, as fractions of the page. The output is the slice alone, so
     * its size is bounded by the screen rather than by the zoom.
     */
    fun renderSlice(index: Int, pageWidth: Float, slice: PdfPaging.Slice): Bitmap? = synchronized(this) {
        if (closed || index < 0 || index >= pages || pageWidth <= 0f) return null
        val pageHeight = pageWidth * aspect(index)
        val w = ((slice.u1 - slice.u0) * pageWidth).roundToInt().coerceIn(1, MAX_WIDTH)
        val h = ((slice.v1 - slice.v0) * pageHeight).roundToInt().coerceIn(1, MAX_HEIGHT)
        runCatching {
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                // Page units are points. Scale the whole page to the zoomed width, then shift
                // it so the slice's top-left corner lands on the bitmap's origin; whatever falls
                // outside the bitmap is simply not drawn.
                val k = pageWidth / page.width
                val matrix = Matrix().apply {
                    setScale(k, k)
                    postTranslate(-slice.u0 * pageWidth, -slice.v0 * pageHeight)
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }.getOrNull()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    /** What opening a file came to. */
    sealed interface Opened {
        class Ready(val doc: PdfDoc) : Opened

        /**
         * Password-protected. [wrong] when a password was given and refused. [canAsk] is false
         * below Android 15, where `PdfRenderer` has no way to be given a password at all.
         */
        class Locked(val wrong: Boolean, val canAsk: Boolean) : Opened

        /** There is no local copy to open: too large, or somewhere that cannot be copied from. */
        data object NoCopy : Opened

        /** Copied, and Android's renderer refused it. */
        data object Unreadable : Opened
    }

    companion object {

        /** A4 at portrait, which is what an unrendered page is assumed to be. */
        const val DEFAULT_ASPECT = 1.414f

        /** How many rendered pages to keep either side of the ones being read, budget allowing. */
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

        /** Whether this Android can be handed a password for a locked PDF. */
        val canAskPassword: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        /**
         * Open whatever [node] is, copying it out of an archive or off a share first.
         *
         * Here rather than in the screen because this file is the one allowed to hold a
         * `java.io.File` (see `check-r3.mjs`) - and a screen that took one would move the
         * handle a layer up, which is the thing that rule is actually about.
         *
         * `PdfRenderer` needs a seekable file descriptor, which rules out a stream from a
         * provider - an archive entry, a remote share, a `content://` URI from another app.
         * `LocalCopy` stages those to local storage first.
         */
        suspend fun open(
            copy: dev.niccc2007.filet.ops.LocalCopy,
            node: dev.niccc2007.filet.vfs.VNode,
            password: String? = null,
        ): Opened {
            val file = copy.of(node) ?: return Opened.NoCopy
            return open(file, password)
        }

        /**
         * Bug identified here: every failure used to come back as one null, so a locked PDF,
         * a damaged one and one that could not be copied all showed the same sentence naming
         * all three causes. `PdfRenderer` says which it is - a locked document throws
         * `SecurityException` - and the screen needs that to offer the one thing that helps.
         */
        fun open(file: File, password: String? = null): Opened {
            val fd = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull() ?: return Opened.Unreadable
            return try {
                val renderer = if (password != null && canAskPassword) {
                    PdfRenderer(fd, LoadParams.Builder().setPassword(password).build())
                } else {
                    PdfRenderer(fd)
                }
                Opened.Ready(PdfDoc(fd, renderer))
            } catch (e: SecurityException) {
                runCatching { fd.close() }
                Opened.Locked(wrong = password != null, canAsk = canAskPassword)
            } catch (e: Exception) {
                runCatching { fd.close() }
                Opened.Unreadable
            }
        }
    }
}

/**
 * The arithmetic of showing a PDF: how big a page bitmap is, which pages are worth holding,
 * where a pinch leaves the picture, and which part of a page is on screen.
 *
 * Pure, with no Android type anywhere in it, because each of these is a decision that can be
 * wrong in a way that only shows on one device with one file - and that is exactly the kind of
 * thing to pin down in a test rather than find on a phone.
 */
object PdfPaging {

    /** A bitmap size in pixels. */
    data class Px(val w: Int, val h: Int)

    /**
     * The bitmap size for a page [width] pixels across with the given [aspect] (height over
     * width), scaled down uniformly until it fits both caps.
     *
     * Bug identified here: the caps used to be applied to width and height separately, so a
     * page taller than `maxH / maxW` of its width - a receipt, a long infographic, a scroll of
     * a web page saved to PDF - came out with its height cut to the cap and its width left
     * alone. `PdfRenderer` fills the bitmap it is given, so the page was squashed vertically.
     */
    fun fit(width: Int, aspect: Float, maxW: Int, maxH: Int): Px {
        if (width <= 0) return Px(1, 1)
        val a = if (aspect > 0f) aspect else PdfDoc.DEFAULT_ASPECT
        val wantH = width * a
        val shrink = min(1f, min(maxW.toFloat() / width, maxH / wantH))
        return Px(
            (width * shrink).roundToInt().coerceAtLeast(1),
            (wantH * shrink).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * How many bytes of whole-page bitmaps the viewer may hold.
     *
     * An eighth of what the runtime reports as the heap limit, between 16 and 64 MB. On a
     * low-end phone with a 128 MB limit that is 16 MB - five pages at 720 px - and on a phone
     * with a big heap it stops at 64 MB rather than growing to fill it, because more cached
     * pages past the next few buy nothing a reader can see.
     */
    fun budget(maxMemory: Long): Long = (maxMemory / 8).coerceIn(MIN_BUDGET, MAX_BUDGET)

    const val MIN_BUDGET = 16L * 1024 * 1024
    const val MAX_BUDGET = 64L * 1024 * 1024

    /**
     * Which pages to hold, in the order to render them.
     *
     * Every [visible] page first, whatever it costs - a blank page on screen is worse than an
     * overspent budget for a moment. Then neighbours outward, the page after before the page
     * before (reading goes forward), up to [keep] each side, stopping at the first one that
     * would take the total past [budget].
     *
     * Bug identified here: the cache used to be "visible plus two either side" with no notion
     * of size. That is bounded on a portrait document at 1x, and unbounded once pages get
     * small - a deck of landscape slides puts a dozen on screen - or bigger, which is what any
     * zoom does.
     */
    fun plan(visible: IntRange, pages: Int, keep: Int, budget: Long, bytesOf: (Int) -> Long): List<Int> {
        if (pages <= 0 || visible.isEmpty()) return emptyList()
        val first = visible.first.coerceIn(0, pages - 1)
        val last = visible.last.coerceIn(first, pages - 1)
        val out = ArrayList<Int>()
        var total = 0L
        for (i in first..last) {
            out += i
            total += bytesOf(i)
        }
        for (d in 1..keep) {
            for (i in intArrayOf(last + d, first - d)) {
                if (i < 0 || i >= pages) continue
                val cost = bytesOf(i)
                if (total + cost > budget) return out
                out += i
                total += cost
            }
        }
        return out
    }

    /** ARGB_8888 bytes for a whole page at [width] px. */
    fun bytesFor(width: Int, aspect: Float): Long {
        val px = fit(width, aspect, PdfDoc.MAX_WIDTH, PdfDoc.MAX_HEIGHT)
        return px.w.toLong() * px.h * 4
    }

    /** The zero-based page a typed 1-based number means, or null when it names no page. */
    fun pageIndex(typed: String, pages: Int): Int? {
        val n = typed.trim().toIntOrNull() ?: return null
        return if (n in 1..pages) n - 1 else null
    }

    // ── zoom ──

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    /** The step a double tap jumps to from 1x. */
    const val TAP_ZOOM = 2.5f

    /**
     * How the list is drawn: scaled by [scale] about the TOP centre of the viewport, then moved
     * sideways by [tx] screen pixels. Vertical movement is not here - it is the list's own
     * scroll, so flinging, the page indicator and the visible range all keep working.
     *
     * Decision: the pivot is the top edge, not the middle. Scaled about the middle, the band
     * of the list above `H/2 - H/2s` is drawn above the screen, and a list cannot scroll above
     * its first item - so the top of page one could not be reached at any zoom. Scaled about
     * the top, nothing is hidden above; what is hidden is the same band at the bottom, and
     * that one is fixed by padding the end of the list by [bottomPad], which a list can always
     * scroll into.
     */
    data class View(val scale: Float = 1f, val tx: Float = 0f)

    /** A pinch frame's result: the new [view], and how far to scroll the list, in list pixels. */
    data class Step(val view: View, val scroll: Float)

    /**
     * One frame of a pinch.
     *
     * The point under the fingers' centroid ([cx], [cy], screen pixels) stays under them: that
     * is what makes a pinch feel attached to the page instead of to the corner of the screen.
     * [zoom] is the frame's ratio of finger spread, [panX]/[panY] the centroid's movement.
     *
     * The derivation, with the list drawn as `X = W/2 + (x - W/2)·s + tx` and `Y = y·s`:
     *
     * - Horizontally the content point under the centroid is kept fixed by
     *   `tx' = (cx - W/2)(1 - z) + tx·z`, then the pan is added.
     * - Vertically there is no offset to change, so the drift `cy·(z - 1)` in screen pixels is
     *   taken up by scrolling the list by that amount over the new scale, and the pan the same
     *   way with the opposite sign - dragging down moves the content down.
     */
    fun pinch(view: View, width: Float, cx: Float, cy: Float, zoom: Float, panX: Float, panY: Float): Step {
        val s = (view.scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val z = s / view.scale
        val tx = (cx - width / 2f) * (1f - z) + view.tx * z + panX
        val scroll = (cy * (z - 1f) - panY) / s
        return Step(View(s, clampTx(tx, s, width)), scroll)
    }

    /** A sideways drag of [dx] screen pixels while zoomed. */
    fun pan(view: View, dx: Float, width: Float): View = view.copy(tx = clampTx(view.tx + dx, view.scale, width))

    /** Never further sideways than the scaled content's own edge. At 1x that is zero. */
    fun clampTx(tx: Float, scale: Float, width: Float): Float {
        val limit = max(0f, (scale - 1f) * width / 2f)
        return tx.coerceIn(-limit, limit)
    }

    /**
     * List pixels of padding after the last page, so the bottom of it can be scrolled up into
     * view at [scale]. The screen shows the top `H/s` of the list; the rest is below the edge.
     */
    fun bottomPad(scale: Float, height: Float): Float = max(0f, height * (1f - 1f / scale))

    /**
     * Where a double tap at ([x], [y]) goes: from 1x in to [TAP_ZOOM] about the tap, from any
     * zoom back to 1x.
     */
    fun doubleTap(view: View, width: Float, x: Float, y: Float): Step {
        val target = if (view.scale > 1.01f) MIN_ZOOM else TAP_ZOOM
        return pinch(view, width, x, y, target / view.scale, 0f, 0f)
    }

    /** The list-pixel row at the middle of the screen, for saying which page is being read. */
    fun middleRow(view: View, height: Float): Float = height / (2f * view.scale)

    // ── the sharp layer ──

    /** Part of a page, as fractions of its width ([u0]..[u1]) and height ([v0]..[v1]). */
    data class Slice(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    /**
     * Which part of a page box is on screen.
     *
     * The box is at ([left], [top]) with size [boxW] x [boxH] in the list's own pixels - the
     * coordinates `LazyColumn` reports, before the zoom is drawn on. The screen, in those same
     * coordinates, is the viewport pulled back through [view]. Null when the box is off screen,
     * or the zoom is 1x, where the base is already sharp.
     *
     * The slice is snapped outward to [SNAP] of the page, so a sideways drag of a few pixels
     * reuses the slice already drawn rather than asking for a new one that differs by a
     * sliver.
     */
    fun slice(
        view: View, width: Float, height: Float,
        left: Float, top: Float, boxW: Float, boxH: Float,
    ): Slice? {
        if (view.scale <= 1.01f || boxW <= 0f || boxH <= 0f) return null
        val s = view.scale
        val x0 = width / 2f - (width / 2f + view.tx) / s
        val x1 = width / 2f + (width / 2f - view.tx) / s
        val y0 = 0f
        val y1 = height / s
        val l = max(x0, left)
        val r = min(x1, left + boxW)
        val t = max(y0, top)
        val b = min(y1, top + boxH)
        if (r <= l || b <= t) return null
        return Slice(
            snapDown((l - left) / boxW),
            snapDown((t - top) / boxH),
            snapUp((r - left) / boxW),
            snapUp((b - top) / boxH),
        )
    }

    /** One fortieth of a page: two to three percent, a few dozen pixels at a typical zoom. */
    const val SNAP = 40

    private fun snapDown(f: Float) = (kotlin.math.floor(f * SNAP) / SNAP).coerceIn(0f, 1f)
    private fun snapUp(f: Float) = (ceil(f * SNAP) / SNAP).coerceIn(0f, 1f)
}
