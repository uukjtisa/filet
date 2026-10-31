package dev.niccc2007.filet.playback

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.vfs.VPath
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * All Music's banner: a faint wall of the covers in it, and the one colour they share.
 *
 * ## Which covers
 *
 * The newest tracks first, so a track that has just arrived is the first new tile. Three kinds
 * are left out, because each makes the wall look broken rather than full: a track with no
 * picture, a picture that is one flat colour with a title on it (a wall of grey squares is a
 * wall of placeholders), and the second track of an album, whose cover is a tile already there.
 * Rebuilt whenever the newest tracks change. The last wall built is also kept on disk (see
 * [MosaicDisk]), but only as a picture to draw while the newest tracks are asked for: the media
 * index stays the record of what is in All Music, and a kept wall whose tracks no longer match is
 * rebuilt, never trusted.
 *
 * ## The colour
 *
 * [ArtPalette] over every chosen cover at once, rather than over the first. One cover's hue is
 * that album's; the hue of the whole wall is the library's, and that is what the Playlists home
 * is washed in.
 */
object CoverMosaic {

    /** The side of the sample each cover is judged on. Looking for flatness, not detail. */
    const val SIDE = 16

    /**
     * The fewest distinct covers a wall is drawn with: three rows of seven, which is the wall
     * across a phone held upright. Below it the wall is a few pictures repeated, and a
     * repeating wall reads as the feature failing rather than as a library, so the banner
     * shows its words and buttons without one.
     */
    const val HOME_WALL_MIN = 21

    /** Twice the floor, so a wider screen fills further before a cover repeats. */
    const val MAX_TILES = 42

    /**
     * How many of the newest tracks are looked at to find [MAX_TILES] worth showing.
     *
     * Several tracks share one album cover, so the ratio is generous; the reads go through the
     * low-priority tier of [TrackCards], so they yield to every row on screen.
     */
    const val CANDIDATES = 200

    /** The side a tile is kept at. A banner tile is around 52dp, so this is crisp at 2.5x. */
    const val TILE_PX = 128

    /** How far from the cover's usual colour a pixel can be and still count as that colour. */
    private const val NEAR = 30

    /** The share of a cover in one colour that makes it a placeholder rather than a picture. */
    private const val FLAT_SHARE = 0.86

    /** Bits two fingerprints may differ by and still be one cover at two sizes. */
    private const val SAME_BITS = 6

    /**
     * Whether a cover is one flat colour with little on it.
     *
     * Measured against the per-channel MEDIAN rather than the mean: a black sleeve with a white
     * title has a mean pulled grey by the lettering, which leaves most of its pixels "far" from
     * it and lets the placeholder through. The median is the background, whatever is printed on
     * it.
     *
     * @param pixels packed 0xRRGGBB.
     */
    fun isBland(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val r = median(pixels) { it shr 16 and 0xFF }
        val g = median(pixels) { it shr 8 and 0xFF }
        val b = median(pixels) { it and 0xFF }
        val near = pixels.count {
            val dr = (it shr 16 and 0xFF) - r
            val dg = (it shr 8 and 0xFF) - g
            val db = (it and 0xFF) - b
            dr * dr + dg * dg + db * db < NEAR * NEAR
        }
        return near >= pixels.size * FLAT_SHARE
    }

    /**
     * A 64-bit average hash: an 8x8 of the cover's lightness, one bit per cell above the mean.
     *
     * The same cover embedded at two resolutions, or re-encoded, lands within a few bits of
     * itself, which is what lets one album contribute one tile when its tracks were tagged
     * separately.
     */
    fun fingerprint(pixels: IntArray, side: Int = SIDE): Long {
        val cell = side / 8
        val luma = DoubleArray(64)
        for (y in 0 until 8) for (x in 0 until 8) {
            var s = 0.0
            for (dy in 0 until cell) for (dx in 0 until cell) {
                s += lightness(pixels[(y * cell + dy) * side + x * cell + dx])
            }
            luma[y * 8 + x] = s
        }
        val mean = luma.average()
        var bits = 0L
        for (i in 0 until 64) if (luma[i] > mean) bits = bits or (1L shl i)
        return bits
    }

    fun sameCover(a: Long, b: Long): Boolean = java.lang.Long.bitCount(a xor b) <= SAME_BITS

    /**
     * The covers worth a tile, as indices into [samples], in order.
     *
     * Null is a track with no picture. Bland ones are skipped, and so is any cover that matches
     * one already chosen.
     */
    fun choose(samples: List<IntArray?>, max: Int = MAX_TILES): List<Int> {
        val out = ArrayList<Int>()
        val prints = ArrayList<Long>()
        for ((i, px) in samples.withIndex()) {
            if (out.size >= max) break
            if (px == null || isBland(px)) continue
            val fp = fingerprint(px)
            if (prints.any { sameCover(it, fp) }) continue
            prints += fp
            out += i
        }
        return out
    }

    /**
     * Which tile each cell of a [cols] by [rows] wall shows, row by row.
     *
     * With fewer tiles than cells, each row starts further round the list, so a picture is not
     * stacked directly over itself - a column of the same cover reads as a rendering fault.
     */
    fun cells(tiles: Int, cols: Int, rows: Int): IntArray {
        if (tiles <= 0 || cols <= 0 || rows <= 0) return IntArray(0)
        // Bug identified here: a shift of half a row is a whole number of turns round the list
        // whenever the tile count divides it - five covers on an eight-wide wall shifted by five
        // - and every row came out identical, a column of each cover. So the shift is taken
        // round the list first, and a shift of nothing becomes a shift of one.
        val shift = ((cols / 2 + 1) % tiles).let { if (it == 0 && tiles > 1) 1 else it }
        return IntArray(cols * rows) { i -> (i % cols + (i / cols) * shift) % tiles }
    }

    private inline fun median(pixels: IntArray, channel: (Int) -> Int): Int {
        val counts = IntArray(256)
        for (p in pixels) counts[channel(p)]++
        var seen = 0
        val half = pixels.size / 2
        for (v in 0 until 256) {
            seen += counts[v]
            if (seen > half) return v
        }
        return 255
    }

    private fun lightness(rgb: Int): Double =
        0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)
}

/** The wall and its colour. [tint] is null when the covers had no colour worth taking. */
data class Mosaic(val tiles: List<ImageBitmap>, val tint: Color?) {
    /** Enough distinct covers to draw the wall at all. See [CoverMosaic.HOME_WALL_MIN]. */
    val full: Boolean get() = tiles.size >= CoverMosaic.HOME_WALL_MIN

    companion object {
        val EMPTY = Mosaic(emptyList(), null)
    }
}

/**
 * The last wall built, by the newest-track list it was built from.
 *
 * So leaving the tab and coming back draws the wall at once instead of rebuilding it from the
 * covers, and the Playlists home and All Music - which both ask for it - build it once.
 */
private object MosaicMemo {
    @Volatile var key: String? = null
    @Volatile var value: Mosaic = Mosaic.EMPTY

    private val reading = kotlinx.coroutines.sync.Mutex()
    @Volatile private var read = false

    /**
     * The wall kept from the last run, read once per process. Both screens that draw it may ask
     * at the same moment on a cold start, and the second waits for the first's answer rather
     * than reading the file again.
     */
    suspend fun restore(context: android.content.Context, vfs: dev.niccc2007.filet.vfs.Vfs): Mosaic =
        reading.withLock {
            if (!read) {
                read = true
                // Only into an empty memo: a wall built in this process is newer than any kept one.
                if (key == null) MosaicDisk.load(context, vfs)?.let { (k, m) -> key = k; value = m }
            }
            value
        }
}

/** All Music's wall, rebuilt when its newest tracks change. */
@Composable
fun rememberAllMusicMosaic(vm: BrowserViewModel): Mosaic {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dirs by vm.prefs.allMusicFolders.collectAsState()
    val minMs by vm.prefs.allMusicMinMs.collectAsState()
    val maxMs by vm.prefs.allMusicMaxMs.collectAsState()
    val heads by produceState<List<VPath>?>(null, dirs, minMs, maxMs) {
        // A query that failed stays null rather than reading as an empty library. An empty list
        // builds an empty wall, and that would blank the kept one over a read that went wrong.
        value = runCatching {
            dev.niccc2007.filet.home.CategoryQuery.readAudio(
                ctx, MusicSortKey.ADDED.name, descending = true, limit = CoverMosaic.CANDIDATES,
                folders = dirs, minMs = minMs, maxMs = maxMs,
            )
        }.getOrNull()?.second?.map { it.node.path }
    }
    val key = heads?.joinToString("\n")
    val mosaic by produceState(MosaicMemo.value, key) {
        // The kept wall first, before the newest tracks are known: a cold start draws what was
        // there last time instead of an empty banner. Whichever run of this is current does it,
        // so the heads arriving mid-read cannot lose it.
        if (value.tiles.isEmpty()) value = MosaicMemo.restore(ctx, vm.vfs)
        val list = heads ?: return@produceState
        if (MosaicCodec.current(MosaicMemo.key, key)) {
            value = MosaicMemo.value
            return@produceState
        }
        val built = buildMosaic(vm.trackCards, list) { partial ->
            // Shown as it fills only when there was nothing before; replacing a full wall with
            // a third of one and growing it back reads as the banner breaking.
            if (value.tiles.isEmpty()) value = partial
        }
        MosaicMemo.key = key
        MosaicMemo.value = built
        value = built
        if (key != null) MosaicDisk.save(ctx, vm.vfs, key, built)
    }
    return mosaic
}

private suspend fun buildMosaic(cards: TrackCards, heads: List<VPath>, publish: (Mosaic) -> Unit): Mosaic {
    val tiles = ArrayList<ImageBitmap>()
    val prints = ArrayList<Long>()
    val samples = ArrayList<IntArray>()
    fun current() = Mosaic(tiles.toList(), tintOf(samples))
    for (path in heads) {
        if (tiles.size >= CoverMosaic.MAX_TILES) break
        val art = runCatching { cards.warm(path).art }.getOrNull() ?: continue
        val made = withContext(Dispatchers.Default) { prepare(art) } ?: continue
        val fp = CoverMosaic.fingerprint(made.first)
        if (prints.any { CoverMosaic.sameCover(it, fp) }) continue
        prints += fp
        samples += made.first
        tiles += made.second.asImageBitmap()
        if (tiles.size % 6 == 0) publish(current())
    }
    return current()
}

/** A cover's judging sample and its tile, or null when it is a placeholder. Never on main. */
private fun prepare(art: Bitmap): Pair<IntArray, Bitmap>? = runCatching {
    // Square, from the middle: a tile is square and a stretched cover is a wrong one.
    val side = minOf(art.width, art.height)
    if (side <= 0) return null
    val square = Bitmap.createBitmap(art, (art.width - side) / 2, (art.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, CoverMosaic.SIDE, CoverMosaic.SIDE, true)
    val px = IntArray(CoverMosaic.SIDE * CoverMosaic.SIDE)
    small.getPixels(px, 0, CoverMosaic.SIDE, 0, 0, CoverMosaic.SIDE, CoverMosaic.SIDE)
    if (small !== square) small.recycle()
    for (i in px.indices) px[i] = px[i] and 0xFFFFFF
    if (CoverMosaic.isBland(px)) {
        if (square !== art) square.recycle()
        return null
    }
    // A copy of our own rather than the cached cover: the card cache evicts under pressure, and
    // a wall holding its bitmaps would keep them past the budget that cache enforces.
    val tile = Bitmap.createScaledBitmap(square, CoverMosaic.TILE_PX, CoverMosaic.TILE_PX, true)
    if (square !== art && square !== tile) square.recycle()
    px to tile
}.getOrNull()

private fun tintOf(samples: List<IntArray>): Color? {
    if (samples.isEmpty()) return null
    val all = IntArray(samples.sumOf { it.size })
    var at = 0
    for (s in samples) {
        s.copyInto(all, at)
        at += s.size
    }
    val r = ArtPalette.of(all)
    return if (r.usable) Color(0xFF000000.toInt() or r.colour) else null
}

/**
 * The wall, faint, fading into the page at its foot.
 *
 * Three rows of square tiles across whatever width it is given. Fades in when it arrives, so a
 * banner that appears after the list has drawn is not a jump.
 */
@Composable
fun MosaicBanner(mosaic: Mosaic, modifier: Modifier) {
    val tiles = mosaic.tiles
    if (!mosaic.full) return
    val ground = MaterialTheme.colorScheme.background
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(500)) }
    Canvas(modifier.clipToBounds()) {
        val rows = 3
        val side = size.height / rows
        val cols = ceil(size.width / side).toInt()
        val map = CoverMosaic.cells(tiles.size, cols, rows)
        val px = ceil(side).toInt() + 1
        for (r in 0 until rows) for (c in 0 until cols) {
            drawImage(
                tiles[map[r * cols + c]],
                dstOffset = IntOffset((c * side).toInt(), (r * side).toInt()),
                dstSize = IntSize(px, px),
                alpha = TILE_ALPHA * shown.value,
            )
        }
        drawRect(
            Brush.verticalGradient(
                0f to ground.copy(alpha = 0.25f),
                0.55f to ground.copy(alpha = 0.72f),
                1f to ground,
            ),
        )
    }
}

/** Faint: it is a backdrop for the header, not a second thing to look at. */
private const val TILE_ALPHA = 0.45f
