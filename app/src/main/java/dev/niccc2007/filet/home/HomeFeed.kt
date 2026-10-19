package dev.niccc2007.filet.home

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A row on the Home overview and in New files.
 *
 * @param at when the file last CHANGED, from its modification time.
 * @param added when the file first APPEARED on the phone - see [HomeFeed] for where that comes
 *   from and what it is not.
 * @param origin the provenance chip (PLAN.md §5.1), null until something has written a biography
 *   record. Rendering "unknown" there would be worse than rendering nothing.
 */
data class FeedItem(val node: VNode, val at: Long, val origin: String? = null, val added: Long = at)

/**
 * New files: every file on the phone's own storage, by when it first appeared.
 *
 * ## The clock is the media index's `DATE_ADDED`
 *
 * This used to be a walk of a short list of "tracked folders", with Filet stamping each path
 * the first time the walk met it. The concept is gone - there is no list to keep, and the tab
 * covers the whole device - and the clock went with it, for a reason worth keeping:
 *
 * On Android 11 and later the shared-storage filesystem is served by the media provider, so
 * every file any app creates there gets a MediaStore row as it is written, and `DATE_ADDED`
 * on that row is the moment the file appeared on the phone. Below 11 a file written without a
 * scan has no row until somebody announces it, which `MediaCatchUp` does. So the answer to
 * "when did this turn up" already exists for every file, device-wide, indexed, and costs one
 * query - no walk, no FUSE traffic, nothing for the battery to notice (issue #13).
 *
 * Filet's own first-seen store was NOT kept as a supplement. Its only writer was the
 * tracked-folder walk; with that gone nothing writes to it, and a store with no writer is not a
 * safety net - it is stranded state that reads as data until the day it is wrong.
 *
 * ## What that means on a real phone
 *
 * - **Installed an hour ago:** the list is full from the first minute. `DATE_ADDED` predates
 *   Filet, so a "first detected" list has no warm-up and no day where everything reads as
 *   arriving at install time.
 * - **Restored or migrated:** a backup restore or a phone-to-phone transfer writes every file
 *   at once, so they all appeared that day and are listed under it. That is true and unhelpful;
 *   the "Last changed" ordering is the way out, and stays.
 * - **Not covered:** remote folders, app-private storage, anything reached only through a
 *   document provider, and the inside of archives - none of it is in the media index. A
 *   remote folder that used to be tracked by hand no longer has a new-files feed.
 * - **Permission:** without all-files access Android 11+ shows only photos, video and music
 *   here, and the screen says so - see [CategoryResult.Refused].
 *
 * ## Bounded
 *
 * [PAGE] rows at a time, newest first, sorted and cut by the DATABASE; the next page loads
 * when the list is scrolled near its end, up to [MAX_HELD]. Past that the list says so and the
 * date jump becomes a range query for the day asked for - see [reach] - so every file stays
 * reachable without fifty thousand rows in memory.
 *
 * Queried on open, on resume, on the refresh button, and after Filet's own writes have been
 * indexed (see `MediaAnnounce.onScanned`) - never on recomposition. While the New files tab is
 * on screen, and only then, a MediaStore observer brings in other apps' files: [watchIndex].
 */
class HomeFeed(private val context: Context, private val scope: CoroutineScope) {

    private val _recent = MutableStateFlow<List<FeedItem>>(emptyList())

    /** The Home card: the newest [SHOWN]. */
    val recent: StateFlow<List<FeedItem>> = _recent.asStateFlow()

    private val _all = MutableStateFlow<List<FeedItem>>(emptyList())

    /** Everything loaded so far, newest first-appearance first. The New files tab draws this. */
    val all: StateFlow<List<FeedItem>> = _all.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _outcome = MutableStateFlow<CategoryResult?>(null)

    /** What the last first-page read came to, for the empty and refused states. */
    val outcome: StateFlow<CategoryResult?> = _outcome.asStateFlow()

    private val _capped = MutableStateFlow(false)

    /** [MAX_HELD] rows are loaded and more exist; the screen says so. */
    val capped: StateFlow<Boolean> = _capped.asStateFlow()

    private val _windowFrom = MutableStateFlow<Long?>(null)

    /**
     * Non-null when the list is not the newest files but a window starting at this day - a
     * date jump past what paging would hold. The screen offers the way back to the newest.
     */
    val windowFrom: StateFlow<Long?> = _windowFrom.asStateFlow()

    /** Supplies the Source chip once provenance exists. */
    var originLookup: (suspend (VPath) -> String?)? = null

    private val lock = Mutex()
    private var exhausted = false
    private var pendingChange: Job? = null

    /** The newest page, replacing whatever is loaded. */
    fun refresh() {
        scope.launch { lock.withLock { firstPage(before = null) } }
    }

    /** Back to the newest files after a date jump. */
    fun toNewest() = refresh()

    /** The next page, if there is one and the cap allows. Called as the list nears its end. */
    fun loadMore() {
        scope.launch {
            lock.withLock {
                val at = NewFilesPaging.nextOffset(_all.value.size, exhausted, MAX_HELD) ?: return@withLock
                _loading.value = true
                val (_, page) = CategoryQuery.readNew(context, at, PAGE, _windowFrom.value)
                exhausted = page.size < PAGE
                _all.value = NewFilesPaging.merge(_all.value, page.map(::item))
                _capped.value = !exhausted && _all.value.size >= MAX_HELD
                _loading.value = false
            }
        }
    }

    /**
     * Make sure files from [dayStartMs] are loaded, for the date jump.
     *
     * Pages forward while the day is within [MAX_HELD] of the newest; past that the list is
     * replaced by a window that STARTS at the end of that day. A jump is a range query, not a
     * scroll through every newer page.
     */
    suspend fun reach(dayStartMs: Long, dayEndMs: Long) {
        lock.withLock {
            while (true) {
                val oldest = _all.value.lastOrNull()?.added ?: return@withLock
                if (oldest <= dayStartMs || exhausted) return@withLock
                val at = NewFilesPaging.nextOffset(_all.value.size, exhausted, MAX_HELD)
                if (at == null) {
                    firstPage(before = dayEndMs)
                    return@withLock
                }
                _loading.value = true
                val (_, page) = CategoryQuery.readNew(context, at, PAGE, _windowFrom.value)
                exhausted = page.size < PAGE
                _all.value = NewFilesPaging.merge(_all.value, page.map(::item))
                _capped.value = !exhausted && _all.value.size >= MAX_HELD
                _loading.value = false
            }
        }
    }

    /**
     * Something on storage changed. Coalesced over [SETTLE_MS], so a copy of a hundred files is
     * one query rather than a hundred.
     */
    fun changed() {
        pendingChange?.cancel()
        pendingChange = scope.launch {
            delay(SETTLE_MS)
            // A jump window is left alone: somebody reading March does not want it replaced by
            // today because a screenshot was taken.
            if (_windowFrom.value == null) lock.withLock { firstPage(before = null) }
        }
    }

    /**
     * Listen to the media index while the New files tab is on screen, and only then.
     *
     * The caller closes the handle when the tab leaves the screen or the app stops - an observer
     * that outlived the tab would be issue #13 coming back through a different door, a standing
     * cost for a list nobody is reading.
     */
    fun watchIndex(): AutoCloseable = CategoryQuery.observe(context) { changed() }

    /** Must hold [lock]. */
    private suspend fun firstPage(before: Long?) {
        _loading.value = true
        val (result, page) = CategoryQuery.readNew(context, 0, PAGE, before)
        _outcome.value = result
        val items = page.map(::item)
        exhausted = page.size < PAGE
        _all.value = items
        _capped.value = false
        _windowFrom.value = before
        if (before == null) publishRecent(items.take(SHOWN))
        _loading.value = false
    }

    private fun item(r: CategoryQuery.NewRow) =
        FeedItem(node = r.node, at = r.node.mtime, added = r.addedAtMs)

    private fun publishRecent(top: List<FeedItem>) {
        _recent.value = top
        val lookup = originLookup ?: return
        // Off the critical path, and only for the rows on the card: a lookup per row of a
        // two-thousand-row page would be the slow version of this screen again.
        scope.launch {
            val withOrigin = top.map { it.copy(origin = runCatching { lookup(it.node.path) }.getOrNull()) }
            if (_recent.value == top) _recent.value = withOrigin
        }
    }

    companion object {
        /** The Home card's rows. */
        const val SHOWN = 8

        /** One query's worth. Two thousand rows of a sorted, indexed column is milliseconds. */
        const val PAGE = 2_000

        /**
         * At most this many rows held. Ten thousand rows is a few megabytes; past it the date
         * jump answers with a range query instead of more paging.
         */
        const val MAX_HELD = 10_000

        /** Quiet time before a change is re-queried. */
        const val SETTLE_MS = 2_000L

        /**
         * Preference keys the retired tracked-folder feature wrote. Removed once, so a phone that
         * used it does not carry a list and a store that nothing reads any more.
         */
        val RETIRED_KEYS = listOf(
            "tracked.folders", "tracked.seeded", "tracked.prunedRemotes", "tracked.seededCommon",
            "home.firstSeen.v4", "home.feed.v1",
        )

        fun forgetRetired(prefs: Prefs) {
            // putString(null) is a remove, whatever type the key was written with - two of these
            // were booleans.
            for (k in RETIRED_KEYS) runCatching { prefs.putString(k, null) }
        }
    }
}

/** The paging decisions, pure, so the order and the cap are tests rather than hopes. */
object NewFilesPaging {

    /** Where the next page starts, or null when there is none to load. */
    fun nextOffset(loaded: Int, exhausted: Boolean, cap: Int): Int? =
        if (exhausted || loaded >= cap) null else loaded

    /**
     * A new page after what is loaded. Offsets move when files arrive between two pages, so a
     * row can appear in both; it is kept once, in its first position. Never re-sorted here - the
     * database's order is the order.
     */
    fun merge(loaded: List<FeedItem>, page: List<FeedItem>): List<FeedItem> {
        if (page.isEmpty()) return loaded
        val seen = HashSet<VPath>(loaded.size + page.size)
        val out = ArrayList<FeedItem>(loaded.size + page.size)
        for (i in loaded) if (seen.add(i.node.path)) out += i
        for (i in page) if (seen.add(i.node.path)) out += i
        return out
    }
}
