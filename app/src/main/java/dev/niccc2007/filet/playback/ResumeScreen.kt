package dev.niccc2007.filet.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.dialogs.PopSeparator
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.Pill
import dev.niccc2007.filet.ui.tabs.PillTone
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What you were part way through, and where you got to.
 *
 * ## One tab, not two
 *
 * Asked for as two - a Music tab and a Video tab - and built as one with two filters. Two
 * destinations that differ only by a filter double the cost of reaching the same list, and the
 * navigation surfaces are already eight entries deep before this one; meanwhile the question
 * somebody has when they open this is "what was I in the middle of", which is answered by one
 * list in time order and made worse by splitting it in half. The filters are there for the
 * times the answer is "the film", and the choice is remembered for the session.
 *
 * If this turns out to be wrong, two destinations is a smaller change than merging them later:
 * the pane kind is declared in `SpecialPanes` and the screen takes a filter already.
 *
 * ## Why the list is cheap
 *
 * Every row is drawn from what `ResumeStore` wrote down - title, length, position - so nothing
 * here stats a file or reads a tag. That matters because the alternative is a list whose
 * opening cost grows with how much media somebody has half-finished, and because a track that
 * has since been deleted should still be listed and should fail when it is TAPPED, with a
 * sentence, rather than silently vanishing from a list of things you remember doing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResumeScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val store = vm.playback.resume
    // Two signals. The store's own moves the moment any player writes a stopped position or a
    // row arrives or leaves - see `ResumeStore.changes`. The view model's moves on a pull-down
    // refresh. Neither is a poll: both move only when something did.
    val revision by vm.resumeRevision.collectAsStateWithLifecycle()
    val changed by store.changes.collectAsStateWithLifecycle()
    var filter by dev.niccc2007.filet.browser.rememberKeptEnum("resume.filter", ResumeFilter.ALL)
    val all = remember(revision, changed) { store.all() }
    // The kind chips first, then the pane's search box: title or where it is.
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    val rows = remember(all, filter, query) {
        all.filter(filter::accepts).filter { dev.niccc2007.filet.ui.TextMatch.matches(query, it.title, it.path) }
    }
    // A tap carries on at once. Decision: no Continue / From the start question in between. The
    // row IS the request to carry on, and a dialog asking it again made every visit two taps;
    // starting over is in the row's hold menu, and a finished row starts over on its own.

    LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("resume")) {
        item { TabHeader("Carry on") }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (f in ResumeFilter.entries) {
                    val lit = f == filter
                    Text(
                        f.label,
                        fontSize = 11.5.sp,
                        color = if (lit) colors.accent else colors.fg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (lit) colors.accent.copy(alpha = 0.14f) else colors.sunken)
                            .combinedClickable(onClick = { filter = f })
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Spacer(Modifier.fillMaxWidth(0f))
            }
        }

        if (rows.isEmpty() && query.isNotBlank() && all.isNotEmpty()) {
            item { dev.niccc2007.filet.ui.NoMatch(query) }
        } else if (rows.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Clock,
                    if (all.isEmpty()) {
                        "Nothing to carry on with"
                    } else {
                        "Nothing ${filter.label.lowercase()} to carry on with"
                    },
                )
            }
        } else {
            item { SectionRow("Where you left off", note = "${rows.size}") }
        }

        items(rows.size) { i ->
            val point = rows[i]
            var menu by remember(point.path) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { vm.resumePoint(point) },
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Anchored at the row's start, so the card opens under the thumbnail that was held;
                // it turns upwards on its own for a row near the bottom of the screen.
                Box {
                    PopMenu(expanded = menu, onDismiss = { menu = false }, below = 8.dp) {
                        PopRow(FiletIcons.Play, "Carry on") { menu = false; vm.resumePoint(point) }
                        PopRow(FiletIcons.Prev, "Start from the beginning") {
                            menu = false
                            vm.resumePoint(point, fromStart = true)
                        }
                        PopRow(FiletIcons.FolderOpen, "Open containing folder") {
                            menu = false
                            runCatching {
                                vm.revealPathInFolder(
                                    dev.niccc2007.filet.vfs.VPath.parse(point.path),
                                )
                            }
                        }
                        PopSeparator()
                        PopRow(FiletIcons.Close, "Forget this one") {
                            menu = false
                            store.forget(point.path)
                            vm.bumpResume()
                        }
                    }
                }
                ResumeThumb(vm, point)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.fillMaxWidth(0.72f)) {
                    dev.niccc2007.filet.browser.ListNameText(
                        text = dev.niccc2007.filet.ui.highlighted(point.title, query, colors.accent),
                        color = MaterialTheme.colorScheme.onSurface,
                        startSp = 13f,
                        lines = 1,
                        fontWeight = FontWeight.Medium,
                    )
                    dev.niccc2007.filet.browser.ListNameText(
                        name = resumeLine(point),
                        color = colors.fg3,
                        startSp = 10.5f,
                        isDir = true,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.fillMaxWidth(0f))
                val tag = finishedTag(point)
                if (tag != null) FinishedTag(tag) else Pill(resumePercent(point), PillTone.Plain)
            }
            // A hairline of how far in it is, under the row. The number says it exactly and
            // this says it at a glance, which is the thing a list is scanned for.
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.sunken),
                )
                Box(
                    Modifier
                        .fillMaxWidth(resumeFraction(point))
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.accent.copy(alpha = 0.7f)),
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (all.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    Text(
                        "Forget everything",
                        fontSize = 11.5.sp,
                        color = colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { store.clear(); vm.bumpResume() })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * "99% FINISHED" for a row played to its end, or null for one still part way. Pure, so the
 * wording and where it starts are a test. Where "finished" starts is [Finished]'s, so the tag
 * and the player that opens the row at 0:00 cannot disagree.
 */
fun finishedTag(point: ResumeStore.Point): String? =
    if (Finished.at(point.positionMs, point.durationMs)) {
        "${Finished.percent(point.positionMs, point.durationMs)}% FINISHED"
    } else {
        null
    }

/**
 * A finished row's tag, in place of its percentage: the settings page's BETA tag in shape, in
 * the colour that means done. Not a percentage pill with a word after it - two numbers saying
 * the same thing side by side reads as two facts.
 */
@Composable
private fun FinishedTag(text: String) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        color = colors.good,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.good.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** "23:41 of 48:02", or "23:41" when the length was never known. Pure. */
fun resumeWhere(point: ResumeStore.Point): String {
    val where = dev.niccc2007.filet.handlers.clockOf(point.positionMs)
    return if (point.durationMs > 0L) {
        where + " of " + dev.niccc2007.filet.handlers.clockOf(point.durationMs)
    } else {
        where
    }
}

/**
 * The row's picture: a frame from where the video was left, or an audio file's own artwork.
 *
 * Generated, not looked up. A video with no cached thumbnail is the common case for a file
 * copied over by hand, and a typed glyph in its place was the gap in this list. The frame is
 * taken at the SAVED position rather than at zero - a better picture than a title card, and a
 * reminder of where they were.
 *
 * The typed icon is only the fallback for a file that yields nothing: audio with no embedded
 * art, a file on a share or inside an archive (see [ResumeThumbs.load]), or a container the
 * platform cannot decode.
 */
@Composable
private fun ResumeThumb(vm: BrowserViewModel, point: ResumeStore.Point) {
    val colors = Filet.colors
    val context = LocalContext.current
    val key = ResumeThumbs.key(point)
    var image by remember(key) { mutableStateOf(ResumeThumbs.cached(key)) }
    var tried by remember(key) { mutableStateOf(image != null || ResumeThumbs.failed(key)) }
    LaunchedEffect(key) {
        if (tried) return@LaunchedEffect
        image = ResumeThumbs.load(context.applicationContext, vm, point)
        tried = true
    }
    Box(
        Modifier
            .size(width = 56.dp, height = 36.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colors.sunken),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            androidx.compose.foundation.Image(
                shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (tried) {
            Icon(
                if (point.video) FiletIcons.Video else FiletIcons.Audio,
                null,
                tint = colors.fg2,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Generated thumbnails for the Carry on list, held in memory.
 *
 * - **Bound:** [MAX] pictures of at most [W] x [H] pixels in RGB_565 - about 32 KB each, under
 *   1.6 MB for the whole cache. Least recently used is evicted first ([Lru]).
 * - **Keyed** on the path and the saved position in half-minute steps, so a film left further
 *   on gets a new frame and one reopened in the same spot does not.
 * - **One at a time.** Extraction runs on a single IO lane. Twenty rows opening twenty decoders
 *   at once is exactly the burst that warms a phone; one lane fills the rows in order.
 * - **Failures are remembered** per key, so a file that will not decode is not retried on every
 *   scroll.
 */
internal object ResumeThumbs {
    const val MAX = 48
    const val W = 160
    const val H = 100

    private val cache = Lru<String, ImageBitmap>(MAX)
    private val failures = Lru<String, Boolean>(MAX * 2)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lane = Dispatchers.IO.limitedParallelism(1)

    fun key(point: ResumeStore.Point): String = point.path + "@" + point.positionMs / 30_000

    fun cached(key: String): ImageBitmap? = cache[key]

    fun failed(key: String): Boolean = failures[key] == true

    suspend fun load(
        context: android.content.Context,
        vm: BrowserViewModel,
        point: ResumeStore.Point,
    ): ImageBitmap? = withContext(lane) {
        val key = key(point)
        cache[key] ?: run {
            val made = runCatching { extract(context, vm, point) }.getOrNull()
            if (made == null) failures.put(key, true) else cache.put(key, made)
            made
        }
    }

    private suspend fun extract(
        context: android.content.Context,
        vm: BrowserViewModel,
        point: ResumeStore.Point,
    ): ImageBitmap? {
        val path = dev.niccc2007.filet.vfs.VPath.parse(point.path)
        // Only a file already on this device. A share or an archive entry would have to be
        // copied out first, and copying a two-gigabyte film to draw a 56dp picture is not a
        // trade anybody would make knowingly.
        if (vm.vfs.isRemote(path) || vm.vfs.osPath(path) == null) return null
        val node = vm.vfs.stat(path) ?: return null
        val uri = vm.localUriFor(node) ?: return null
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val raw: android.graphics.Bitmap = (if (point.video) frameOf(r, point.positionMs) else artOf(r)) ?: return null
            val (w, h) = fitInside(raw.width, raw.height, W, H)
            val sized = if (raw.width == w && raw.height == h) {
                raw
            } else {
                android.graphics.Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() }
            }
            val small = if (sized.config == android.graphics.Bitmap.Config.RGB_565) {
                sized
            } else {
                sized.copy(android.graphics.Bitmap.Config.RGB_565, false)?.also { sized.recycle() } ?: sized
            }
            return small.asImageBitmap()
        } finally {
            runCatching { r.release() }
        }
    }

    /** A frame at the saved position, scaled by the decoder where it can be; at zero if that fails. */
    private fun frameOf(r: android.media.MediaMetadataRetriever, positionMs: Long): android.graphics.Bitmap? {
        val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val us = positionMs * 1000
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(us, opt, W, H) ?: r.getScaledFrameAtTime(0, opt, W, H)
        } else {
            r.getFrameAtTime(us, opt) ?: r.getFrameAtTime(0, opt)
        }
    }

    /** Embedded artwork, decoded at a fraction of its size rather than whole and then shrunk. */
    private fun artOf(r: android.media.MediaMetadataRetriever): android.graphics.Bitmap? {
        val bytes = r.embeddedPicture ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, W, H)
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

/** The largest power-of-two step that still leaves a picture at least [w] x [h]. Pure. */
fun sampleFor(srcW: Int, srcH: Int, w: Int, h: Int): Int {
    var n = 1
    while (srcW / (n * 2) >= w && srcH / (n * 2) >= h) n *= 2
    return n
}

/** [srcW] x [srcH] scaled down to fit inside [w] x [h], aspect kept, never scaled up. Pure. */
fun fitInside(srcW: Int, srcH: Int, w: Int, h: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return Pair(w, h)
    val k = minOf(1f, w.toFloat() / srcW, h.toFloat() / srcH)
    // Rounded, then clamped: truncating turned 1920 * (100 / 1920f) = 99.99999 into 99.
    return Pair(
        kotlin.math.round(srcW * k).toInt().coerceIn(1, w),
        kotlin.math.round(srcH * k).toInt().coerceIn(1, h),
    )
}

/** A least-recently-used map of fixed size. Thread-safe. Pure. */
class Lru<K, V>(val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    val size: Int
        @Synchronized get() = map.size
}

/** The two halves of the media this app plays, plus everything. */
enum class ResumeFilter(val label: String) {
    ALL("Everything"),
    MUSIC("Music"),
    VIDEO("Video");

    fun accepts(point: ResumeStore.Point): Boolean = when (this) {
        ALL -> true
        MUSIC -> !point.video
        VIDEO -> point.video
    }
}

/** "12:40 of 1:38:02  ·  yesterday". Pure, so the wording can be tested. */
fun resumeLine(point: ResumeStore.Point, now: Long = System.currentTimeMillis()): String {
    val where = dev.niccc2007.filet.handlers.clockOf(point.positionMs)
    val total = if (point.durationMs > 0L) {
        " of " + dev.niccc2007.filet.handlers.clockOf(point.durationMs)
    } else {
        ""
    }
    return where + total + "  ·  " + agoOf(now - point.at)
}

/** How far through, as a percentage, or a dash when the length was never known. */
fun resumePercent(point: ResumeStore.Point): String =
    if (point.durationMs <= 0L) "—" else "${(resumeFraction(point) * 100).toInt()}%"

fun resumeFraction(point: ResumeStore.Point): Float =
    if (point.durationMs <= 0L) {
        0f
    } else {
        (point.positionMs.toFloat() / point.durationMs).coerceIn(0f, 1f)
    }

/**
 * How long ago, in words.
 *
 * Coarse on purpose. "Three days ago" is what somebody wants from this list; the minute it
 * happened is a fact about a log, and printing it invites reading the list as one.
 */
fun agoOf(elapsedMs: Long): String {
    val minutes = elapsedMs / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 60L * 24 -> "${minutes / 60}h ago"
        minutes < 60L * 48 -> "yesterday"
        minutes < 60L * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> "a while ago"
    }
}
