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
 * @param added when the file first APPEARED on the phone, as the media index says - see
 *   [HomeFeed] for where that comes from and what it is not.
 * @param origin the provenance chip (PLAN.md §5.1), null until something has written a biography
 *   record. Rendering "unknown" there would be worse than rendering nothing.
 */
data class FeedItem(
    val node: VNode,
    val at: Long,
    val origin: String? = null,
    val added: Long = at,
    /** Filet's earlier first-seen, when [HomeFeed.firstSeenLookup] found one; see [arrived]. */
    val remembered: Long? = null,
    /** Which list this row was read for, so [listedAt] means the same thing as the list. */
    val by: HistorySort = HistorySort.DATE_ADDED,
) {
    /** When it arrived, corrected by Filet's memory if that applied. */
    val arrived: Long get() = remembered ?: added

    /** The date the row is listed under: the one the list is ordered by. */
    val listedAt: Long get() = if (by == HistorySort.DATE_MODIFIED) at else arrived
}

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
 *   the "By date modified" list is the way out, and stays.
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

    /**
     * Folders whose files are left out of the feed, read live from the preferences.
     *
     * A lambda rather than a stored set so a block takes effect on the next page rather than on
     * the next launch - the moment somebody blocks a folder is the moment they want it gone.
     */
    var blockedFolders: () -> Set<String> = { emptySet() }

    /**
     * Name prefixes to leave out, with everything under a folder that matches one.
     *
     * A lambda like [blockedFolders] beside it, for the same reason: this is read at the
     * moment a page is fetched, so changing the rule takes effect on the next page rather
     * than needing the feed to be rebuilt.
     */
    var feedPrefixes: () -> Set<String> = { emptySet() }

    /**
     * Filet's own folders and root files, always excluded and not a preference. See
     * `FeedExclusions.ownPaths`.
     *
     * Separate from [blockedFolders] so a user clearing their own blocked list cannot
     * accidentally start seeing Filet's cache writes reported as new files on their phone.
     */
    var ownPaths: () -> Set<String> = { emptySet() }

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

    /**
     * Which list this is: arrivals by date added, or the whole phone by date modified.
     *
     * Read at the moment a first page is fetched, like [blockedFolders], so switching the tab's
     * control and refreshing is all a change takes - and the Home card follows, because it is
     * cut from the same first page.
     */
    var order: () -> HistorySort = { HistorySort.DATE_ADDED }

    /**
     * Filet's first-seen for a whole page of paths in one query.
     *
     * An editor that saves by deleting and recreating gets a fresh `DATE_ADDED`, so a file that
     * arrived on the 7th and was edited on the 10th would otherwise move to the 10th in the
     * date-added list. The earlier of the two is used. Only ever moves a date EARLIER, and only
     * in the date-added list: every source proves "it was here by then", so the earliest is the
     * truest, and the date-modified list is the system's alone.
     *
     * Null is the ONLY thing that turns this off, and it does so silently: a date-added list
     * with no lookup wired is the plain media index, indistinguishable from the feature not
     * existing. FiletApp wires it at startup; keep it that way.
     */
    var firstSeenLookup: (suspend (List<VPath>) -> Map<VPath, Long>)? = null

    private val lock = Mutex()
    private var exhausted = false
    private var pendingChange: Job? = null

    /** The order the rows now held were read in. Paging must continue in the same one. */
    private var loadedBy: HistorySort = HistorySort.DATE_ADDED

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
                appendPage(at, PAGE)
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
                // The DATABASE's date for the order in use, never a corrected one: this decides
                // what the next query asks for, and the query knows only the column.
                val oldest = _all.value.lastOrNull()?.let { NewFilesPaging.dbTime(it, loadedBy) } ?: return@withLock
                if (oldest <= dayStartMs || exhausted) return@withLock
                val at = NewFilesPaging.nextOffset(_all.value.size, exhausted, MAX_HELD)
                if (at == null) {
                    firstPage(before = dayEndMs)
                    return@withLock
                }
                _loading.value = true
                appendPage(at, PAGE)
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

    /**
     * Must hold [lock].
     *
     * Two reads, and the split is the fix for a tab that showed nothing on first open: the old
     * first page was [PAGE] rows, so nothing appeared until two thousand rows had been read,
     * converted and grouped. Now [FIRST_PAGE] rows are read and PUBLISHED - enough to fill the
     * screen and the Home card - and the rest of the first page follows behind them.
     */
    private suspend fun firstPage(before: Long?) {
        _loading.value = true
        loadedBy = order()
        val (result, page) = read(0, FIRST_PAGE, before)
        _outcome.value = result
        val items = corrected(page.map(::item))
        exhausted = page.size < FIRST_PAGE
        _all.value = items
        _capped.value = false
        _windowFrom.value = before
        if (before == null) publishRecent(NewFilesPaging.cardRows(items, SHOWN))
        if (!exhausted) appendPage(items.size, PAGE - FIRST_PAGE)
        _loading.value = false
    }

    /** Must hold [lock]. Reads [count] rows from [offset] in the loaded order and appends them. */
    private suspend fun appendPage(offset: Int, count: Int) {
        val (_, page) = read(offset, count, _windowFrom.value)
        exhausted = page.size < count
        _all.value = NewFilesPaging.merge(_all.value, corrected(page.map(::item)))
        _capped.value = !exhausted && _all.value.size >= MAX_HELD
    }

    private suspend fun read(offset: Int, limit: Int, before: Long?) =
        CategoryQuery.readNew(
            context, offset, limit, before,
            // Filet's own folders are added here rather than stored among the user's blocked
            // ones, so clearing that list cannot start reporting the app's cache writes as
            // new files on the phone.
            blockedFolders() + ownPaths(),
            feedPrefixes(),
            loadedBy,
        )

    /**
     * Filet's memory applied to a date-added page: one batch lookup for the whole page, never one
     * per row. Always on - the date-added list exists to be the one edits cannot reorder.
     */
    private suspend fun corrected(items: List<FeedItem>): List<FeedItem> {
        if (loadedBy != HistorySort.DATE_ADDED) return items
        val lookup = firstSeenLookup ?: return items
        val seen = runCatching { lookup(items.map { it.node.path }) }.getOrDefault(emptyMap())
        if (seen.isEmpty()) return items
        return items.map { NewFilesPaging.remember(it, seen[it.node.path]) }
    }

    private fun item(r: CategoryQuery.NewRow) =
        FeedItem(node = r.node, at = r.node.mtime, added = r.addedAtMs, by = loadedBy)

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
        /**
         * Rows published for the Home card: as many as any card can be set to show. Each card
         * takes its own count from these - see [FeedCard].
         */
        const val SHOWN = FeedCard.MAX_COUNT

        /** One query's worth. Two thousand rows of a sorted, indexed column is milliseconds. */
        const val PAGE = 2_000

        /**
         * The rows read and shown before the rest of the first page: a screenful and the Home
         * card. The same split the categories use - see CategoryScreen.
         */
        const val FIRST_PAGE = 120

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
            // The switch the date-added correction once sat behind; it is always on now.
            "home.keepAddedThroughEdits",
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

    /**
     * The date the DATABASE ordered [item] by: what the next query has to continue from. Never
     * the memory-corrected one, which the query cannot know about.
     */
    fun dbTime(item: FeedItem, by: HistorySort): Long =
        if (by == HistorySort.DATE_MODIFIED) item.at else item.added

    /**
     * [item] with Filet's first-seen applied: kept only when it is EARLIER than what the system
     * says. A later one proves nothing - the system already knew it was here before then.
     */
    fun remember(item: FeedItem, firstSeen: Long?): FeedItem =
        if (firstSeen != null && firstSeen > 0 && firstSeen < item.added) item.copy(remembered = firstSeen) else item

    /**
     * The Home card's rows: the newest by the date the list is listed under.
     *
     * Not simply the first rows of the page. With Filet's memory applied, a file the system
     * re-dated to today but that Filet knows arrived last month sits at the top of the query and
     * is not new - the card would otherwise show an old file as the newest thing on the phone.
     */
    fun cardRows(items: List<FeedItem>, n: Int): List<FeedItem> =
        items.sortedByDescending { it.listedAt }.take(n)

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
