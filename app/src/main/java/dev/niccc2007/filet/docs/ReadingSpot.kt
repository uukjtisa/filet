package dev.niccc2007.filet.docs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a reading spot counts in, which is a different thing on each kind of screen.
 *
 * @param finishes true where having the end on screen means the file has been read through, so
 *   there is nothing to continue. See [ReadingSpots.leaving].
 */
enum class SpotKind(val tag: String, val finishes: Boolean) {
    /** A block of a Markdown, Word or slide document. */
    DOC("d", true),

    /** A page of a PDF. */
    PAGE("p", true),

    /** A row of a sheet, with its sheet and first column. */
    ROW("r", false),

    /** A line in the text editor. */
    LINE("l", false);

    companion object {
        fun of(tag: String?): SpotKind? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * One place in one file.
 *
 * @param path the file's address as `VPath` renders it, which is the key.
 * @param item the block, page, row or line at the top of the screen.
 * @param within how far into that item the screen started, from 0 to 1. A fraction rather than
 *   pixels, so a spot saved at one text size lands in the same place at another.
 * @param part the sheet, for a workbook. 0 everywhere else.
 * @param column the first column on screen, for a sheet. 0 everywhere else.
 * @param label what the card says the spot is: a heading, a page, a cell, a line.
 * @param anchor the slug of the heading [label] names, for finding it again in a changed file.
 * @param stamp the file's modified time when this was saved, 0 when it was not known.
 * @param at when this was saved.
 */
data class ReadingSpot(
    val path: String,
    val kind: SpotKind,
    val item: Int,
    val within: Float = 0f,
    val part: Int = 0,
    val column: Int = 0,
    val label: String,
    val anchor: String = "",
    val stamp: Long = 0L,
    val at: Long = 0L,
)

/**
 * Where you were in a file, kept per file and offered when it opens again.
 *
 * ## What the reader sees
 *
 * A file always opens at the top. If it has a spot, a small card slides in at the top right for
 * [SHOWN_MS] - "Continue where you left off" and where that was - and a tap on the card scrolls
 * there. Its ✕, or letting it run out, leaves the file at the top. Nothing else: the reader never
 * moves on its own, because a file that opens somewhere other than its start has to be worked
 * out before it can be read.
 *
 * ## When a spot is kept, and when it is forgotten
 *
 * - Leaving a file at its top changes nothing. Opening starts there anyway, so a visit that did
 *   not move says nothing about where you were - and closing straight after the card offered
 *   page forty must not throw page forty away.
 * - Leaving a document or a PDF with its end on screen forgets it: having read to the end is the
 *   one state where "continue" has no meaning. A sheet's last rows and a text file's last lines
 *   are kept, because that is where a total is, and a log's newest entry.
 * - [KEEP] files, newest first, and older ones drop off the end.
 * - A spot not saved again for [MAX_AGE_MS] is gone. A place in a file nobody has opened in
 *   three months is not a place anybody is in the middle of.
 * - A file changed since its spot was saved keeps the spot. A document goes to the same heading
 *   if it still has it; anything else is held inside what the file has become. See [goal].
 *
 * ## Its own file
 *
 * Not in `Prefs`, for the reason `ResumeStore` is not: this turns over with every file closed,
 * and a corrupt write must not take the settings with it.
 */
class ReadingSpots private constructor(context: Context) {

    private val sp = context.getSharedPreferences("filet.reading", Context.MODE_PRIVATE)

    /** The parsed rows, newest first, or null before the first read. Guarded by `this`. */
    private var rows: List<ReadingSpot>? = null

    private fun current(now: Long): List<ReadingSpot> =
        rows ?: prune(decode(sp.getString(KEY, null)), now).also { rows = it }

    /** The spot for one file on one kind of screen, or null when there is none worth offering. */
    fun spotOf(path: String, kind: SpotKind, now: Long = System.currentTimeMillis()): ReadingSpot? =
        synchronized(this) {
            current(now).firstOrNull { it.path == path && it.kind == kind && fresh(it, now) }
        }

    /**
     * The file is being left: keep where it is, forget it, or leave the stored spot alone, by
     * [leaving].
     *
     * @param top the file's start is still on screen.
     * @param end its end is.
     * @param spot where it is, asked for only when it is going to be kept.
     */
    fun left(path: String, kind: SpotKind, top: Boolean, end: Boolean, spot: () -> ReadingSpot) {
        synchronized(this) { lastLeft[path + '\u0000' + kind.name] = if (top) null else spot() }
        when (leaving(kind, top, end)) {
            Leaving.STAY -> Unit
            Leaving.FORGET -> forget(path, kind)
            Leaving.SAVE -> put(spot())
        }
    }

    /**
     * Exactly where each file was last left, for as long as the process lives, and null for a file
     * left at its top - for Back into a file a link had been followed out of.
     *
     * Not the stored spot. Its rules are for coming back days later, and two of them are wrong
     * two seconds later: a file left with its end on screen is forgotten, so a link at the foot of
     * a page would return to its top, and a file left at its top keeps an older spot, so a link
     * near the top would return somewhere the reader never was this time.
     */
    private val lastLeft = object : LinkedHashMap<String, ReadingSpot?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadingSpot?>?) = size > RETURNS
    }

    fun returnSpot(path: String, kind: SpotKind): ReadingSpot? =
        synchronized(this) { lastLeft[path + '\u0000' + kind.name] }

    fun put(spot: ReadingSpot) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val row = spot.copy(at = now)
            save(prune(current(now).filterNot { it.path == row.path && it.kind == row.kind } + row, now))
        }
    }

    fun forget(path: String, kind: SpotKind) {
        synchronized(this) {
            val before = current(System.currentTimeMillis())
            if (before.none { it.path == path && it.kind == kind }) return
            save(before.filterNot { it.path == path && it.kind == kind })
        }
    }

    /** Caller holds `this`. */
    private fun save(list: List<ReadingSpot>) {
        rows = list
        // apply(), not commit(): the write is the platform's background writer's, so leaving a
        // document on the main thread costs a map update and nothing on the disk.
        sp.edit().putString(KEY, encode(list)).apply()
    }

    /** What leaving a file does to its stored spot. */
    enum class Leaving { SAVE, FORGET, STAY }

    companion object {
        private const val KEY = "spots"

        /**
         * How many files to remember.
         *
         * Two hundred, which at a couple of hundred bytes a row is about forty kilobytes. The cap
         * is there so the file cannot grow without bound, not because the rows cost anything.
         */
        const val KEEP = 200

        /** Files whose last place is held for Back. A chain of links is never this deep. */
        private const val RETURNS = 64

        /** Ninety days. See the note on the class. */
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        /** How long the card stays up before it goes by itself. */
        const val SHOWN_MS = 10_000L

        fun leaving(kind: SpotKind, top: Boolean, end: Boolean): Leaving = when {
            // First: a file short enough to have both on one screen was never anywhere else.
            top -> Leaving.STAY
            end && kind.finishes -> Leaving.FORGET
            else -> Leaving.SAVE
        }

        fun fresh(spot: ReadingSpot, now: Long): Boolean = now - spot.at <= MAX_AGE_MS

        /** Newest first, one row per file and kind, nothing stale, at most [KEEP]. */
        fun prune(list: List<ReadingSpot>, now: Long): List<ReadingSpot> =
            list.sortedByDescending { it.at }
                .filter { fresh(it, now) }
                .distinctBy { it.path to it.kind }
                .take(KEEP)

        /**
         * Where a spot leads in the file as it is now.
         *
         * The same file goes back to the exact item and the fraction into it. A changed one goes
         * to the heading the spot was under, found by its slug, at the heading's own start -
         * a paragraph count is the first thing an edit above it breaks, and the heading is what
         * the card promised. With no heading to find, the item is held inside the file, which
         * may have grown shorter.
         *
         * @param count how many items the file has now.
         * @param anchors the document's slugs to block indexes; empty for anything that is not one.
         */
        fun goal(spot: ReadingSpot, stamp: Long, count: Int, anchors: Map<String, Int> = emptyMap()): Goal {
            if (count <= 0) return Goal(0, 0f)
            val changed = spot.stamp != 0L && stamp != 0L && spot.stamp != stamp
            if (changed) {
                val heading = spot.anchor.takeIf { it.isNotEmpty() }?.let { anchors[it] }
                if (heading != null) return Goal(heading.coerceIn(0, count - 1), 0f)
            }
            val item = spot.item.coerceIn(0, count - 1)
            return Goal(item, if (item == spot.item) spot.within.coerceIn(0f, 1f) else 0f)
        }

        /** The file is a JSON array of short-keyed objects, the way `ResumeStore` writes its own. */
        fun encode(list: List<ReadingSpot>): String {
            val out = JSONArray()
            for (r in list) {
                out.put(
                    JSONObject()
                        .put("p", r.path)
                        .put("k", r.kind.tag)
                        .put("i", r.item)
                        .put("f", r.within.toDouble())
                        .put("s", r.part)
                        .put("c", r.column)
                        .put("l", r.label)
                        .put("a", r.anchor)
                        .put("m", r.stamp)
                        .put("w", r.at),
                )
            }
            return out.toString()
        }

        fun decode(raw: String?): List<ReadingSpot> {
            if (raw.isNullOrBlank()) return emptyList()
            // Best effort throughout: the cost of giving up on a bad row is one forgotten place,
            // and a store that throws has made opening a file worse than not having one.
            return runCatching {
                val array = JSONArray(raw)
                val out = ArrayList<ReadingSpot>(array.length())
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val path = o.optString("p")
                    val kind = SpotKind.of(o.optString("k")) ?: continue
                    if (path.isEmpty()) continue
                    out += ReadingSpot(
                        path = path,
                        kind = kind,
                        item = o.optInt("i").coerceAtLeast(0),
                        within = o.optDouble("f", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                        part = o.optInt("s").coerceAtLeast(0),
                        column = o.optInt("c").coerceAtLeast(0),
                        label = o.optString("l"),
                        anchor = o.optString("a"),
                        stamp = o.optLong("m"),
                        at = o.optLong("w"),
                    )
                }
                out
            }.getOrDefault(emptyList())
        }

        @Volatile private var instance: ReadingSpots? = null

        /** The one store for the process, so every screen writes through the same rows. */
        fun of(context: Context): ReadingSpots =
            instance ?: synchronized(this) {
                instance ?: ReadingSpots(context.applicationContext).also { instance = it }
            }
    }
}

/** Where to scroll for a spot: the item, and how far into it. */
data class Goal(val item: Int, val within: Float)

/**
 * The saved spot for a file, read off the main thread, or null.
 *
 * @param offer false once the screen has already offered it, so a reader composed again over the
 *   same page - flipping the editor to its page and back - does not offer it a second time.
 */
@Composable
internal fun rememberSavedSpot(path: String?, kind: SpotKind, offer: Boolean = true): ReadingSpot? {
    val context = LocalContext.current
    val returning = LocalViewerReturn.current
    var spot by remember(path, kind) { mutableStateOf<ReadingSpot?>(null) }
    LaunchedEffect(path, kind, offer, returning) {
        if (path == null || !offer) return@LaunchedEffect
        spot = withContext(Dispatchers.IO) {
            val spots = ReadingSpots.of(context)
            if (returning) spots.returnSpot(path, kind) else spots.spotOf(path, kind)
        }
    }
    return spot
}

/**
 * True on a screen that Back has just returned to from a file a link opened. Its spot is then the
 * place it was left, and [SpotCard] goes there instead of offering it. Provided by the handler
 * host from `OpenRequest.returning`.
 */
val LocalViewerReturn = compositionLocalOf { false }

/**
 * Run [save] when the screen goes, and when the app leaves the foreground - the second because
 * a process in the background can be ended without the screen ever being disposed.
 */
@Composable
internal fun SaveSpotOnLeave(key: Any?, save: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(save)
    DisposableEffect(key, owner) {
        val watch = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        owner.lifecycle.addObserver(watch)
        onDispose {
            owner.lifecycle.removeObserver(watch)
            latest()
        }
    }
}

/**
 * The card that offers a saved spot: top right, over the file, for [ReadingSpots.SHOWN_MS].
 *
 * The shared [dev.niccc2007.filet.ui.ContinueCard], the one the media players offer their place
 * with, so a document and a song ask the same way. It slides in after the file is up, so the file
 * is seen opening at its top first.
 *
 * @param onGo the card was tapped; scroll to the spot.
 */
@Composable
internal fun SpotCard(spot: ReadingSpot?, modifier: Modifier = Modifier, onGo: (ReadingSpot) -> Unit) {
    val returning = LocalViewerReturn.current
    val go by rememberUpdatedState(onGo)
    // Coming back to a page is not opening it again: no question, just the place it was.
    LaunchedEffect(spot) { if (spot != null && returning) go(spot) }
    dev.niccc2007.filet.ui.ContinueCard(
        key = spot.takeIf { !returning },
        title = "Continue where you left off",
        detail = spot?.label.orEmpty(),
        closeLabel = "Stay at the top",
        modifier = modifier,
        holdMs = ReadingSpots.SHOWN_MS,
    ) { spot?.let(go) }
}
