package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.FileIndex
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder size and contents for the Details sheet: the last known figure at once, then the
 * real one.
 *
 * Two caches, in the order they are tried:
 * - a walk finished earlier in this run, held here, which is the freshest thing there is;
 * - the search index, which survives a restart and answers with one recursive query.
 *
 * The index's answer is itself memoised - see [indexTally]. It has to be: aggregating a subtree
 * is cheap once for a details sheet and ruinous once per visible row, which is what putting the
 * folder total on a list row turned it into.
 *
 * Whichever is newer is shown while a walk through the VFS confirms it ([FolderSizes.pick]).
 * The walk is never written back into the index. The index's rows belong to the crawl and its
 * generation sweep, and a second writer stamping rows from a Details sheet would make "this
 * row was confirmed by the running pass" untrue.
 *
 * The walk outlives the sheet, on purpose. It runs in the graph's scope, so closing the sheet
 * halfway through a large folder still leaves an answer for the next time it is opened. A walk
 * that runs past [LEDGER_AFTER_MS] puts a row in the activity sheet with a Stop on it, so
 * nothing runs that the user cannot see and end; a quick one never appears there.
 */
class FolderMeasure(
    private val vfs: Vfs,
    private val index: FileIndex,
    private val ledger: JobLedger,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val walk: MutableStateFlow<WalkState>, var job: Job?)

    private val walks = HashMap<VPath, Entry>()
    private val confirmed = object : LinkedHashMap<VPath, CachedTally>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VPath, CachedTally>?) = size > MEMO
    }

    /**
     * The index's answer for a folder, held so it is derived once rather than per row.
     *
     * [gen] is the generation the index was stamping when this was computed and [at] is when
     * that was. See [indexTally] for what each of them invalidates.
     */
    private class IndexMemo(val tally: CachedTally?, val gen: Long, val at: Long)

    private val memo = object : LinkedHashMap<VPath, IndexMemo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VPath, IndexMemo>?) = size > MEMO
    }

    /** Folders waiting to be walked, and the set form of the same thing for a cheap contains. */
    private val queue = ArrayDeque<VPath>()
    private val pending = LinkedHashSet<VPath>()
    private var pump: Job? = null

    /** The cached figure worth showing now, or null if nothing is known. */
    suspend fun cached(path: VPath): CachedTally? {
        val walked = synchronized(confirmed) { confirmed[path] }
        return FolderSizes.pick(walked, indexTally(path))
    }

    /**
     * What a ROW should show for this folder right now. See [FolderTotal].
     *
     * Separate from [cached] because a row and the details sheet want different things from the
     * same lookup: the sheet names the source and the timestamp in words, while a row has one
     * number's worth of space and needs to know only whether its figure is final, provisional, or
     * not there at all.
     *
     * **Only a completed walk is ever [FolderTotal.Settled].** The index's figure is a floor and
     * comes back as [FolderTotal.AtLeast] however long ago the crawl ran, because no amount of
     * elapsed time turns "the part that got indexed" into "the folder". Getting this wrong is the
     * defect described on [FolderTotal]: 703 MB printed as final for a 39 GB folder.
     */
    suspend fun total(path: VPath): FolderTotal {
        val best = cached(path) ?: return FolderTotal.Unknown
        val bytes = best.tally.bytes
        if (bytes < 0) return FolderTotal.Unknown
        val walking = walking(path)
        val firm = best.source == TallySource.WALK && !best.tally.partial
        return when {
            walking -> FolderTotal.Confirming(bytes)
            firm -> FolderTotal.Settled(bytes)
            else -> FolderTotal.AtLeast(bytes)
        }
    }

    /**
     * Ask for [path] to be walked for real, one folder at a time.
     *
     * ## Why there is a queue and not just a walk
     *
     * The rows are what call this, and a screenful is forty of them. Starting a walk per row
     * would put forty recursive tree walks on the filesystem at once during a fling, each one
     * stat-ing thousands of entries - which is the exact cost that made the row total a lookup
     * rather than a measurement in the first place.
     *
     * So the folders queue and a single worker drains them. The cost of real folder sizes is one
     * background walk at a time, whatever is on screen. A row that scrolls away before its turn
     * is still measured, because the result is cached and the next row to ask gets it instantly -
     * dropping the request would mean a list that never finishes measuring itself.
     *
     * Already walking, already walked, or not worth walking, and this does nothing. A remote
     * folder is never walked unasked ([FolderSizes.walksOnOpen]): it costs a network round trip
     * per directory, and a list of them would be minutes of someone's data.
     */
    fun measure(path: VPath) {
        synchronized(pending) {
            if (path in pending) return
            if (pending.size >= QUEUE_MAX) return
            pending += path
            queue.addLast(path)
        }
        startPump()
    }

    private fun startPump() = synchronized(pending) {
        if (pump?.isActive == true) return@synchronized
        pump = scope.launch {
            while (true) {
                val next = synchronized(pending) { queue.removeFirstOrNull() } ?: return@launch
                try {
                    if (worthWalking(next)) walk(next).first { it !is WalkState.Running }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One folder failing is not the queue failing. The walk itself records the
                    // reason; the next folder still gets measured.
                } finally {
                    synchronized(pending) { pending -= next }
                }
            }
        }
    }

    /** Whether a queued folder still needs the walk by the time its turn comes. */
    private fun worthWalking(path: VPath): Boolean {
        if (walking(path)) return false
        val had = synchronized(confirmed) { confirmed[path] }
        if (had != null && !had.tally.partial) return false
        return FolderSizes.walksOnOpen(runCatching { vfs.isRemote(path) }.getOrDefault(true))
    }

    /** Whether a walk for this exact folder is in flight. */
    fun walking(path: VPath): Boolean =
        synchronized(walks) { walks[path]?.job?.isActive == true }

    /**
     * The index's figure for [path], computed at most once per crawl generation.
     *
     * ## Why this is cached and the walk cache was not enough
     *
     * Bug identified here: this recomputed on EVERY call, and the call is per visible row. One
     * call streams every indexed row beneath the folder through a [TallyBuilder] - for a folder
     * near a storage root that is the whole index - so putting the folder total on a list row
     * meant a full subtree aggregation per folder per lookup. The details sheet felt instant and
     * the rows felt slow, from the same function, because the sheet asks once and a list asks
     * forty times and again on every scroll.
     *
     * ## What invalidates it
     *
     * The generation the index is stamping, which changes when a pass starts, so a re-crawl
     * cannot be served a figure from before it. That alone would hold a stale figure for the
     * whole of a running pass, which can be minutes, and during a pass the rows underneath are
     * exactly what is changing - so while one is running the entry also expires after
     * [WHILE_CRAWLING_MS]. Between passes the generation is the only key it needs and the
     * figure is held indefinitely, which is the common case and the one worth being free.
     *
     * A null result is cached too. "The index holds nothing under this folder" costs the same
     * full streaming read to establish as a real figure does, and it is the answer for every
     * folder on a device where indexing is off.
     */
    private suspend fun indexTally(path: VPath): CachedTally? {
        val status = index.status.value
        val clock = now()
        // Checked before any dispatch: a hit is a map lookup on the calling thread, which is
        // the entire point. Wrapping the hit in `withContext` would put a thread hand-off on
        // the hot path and give most of the saving back.
        synchronized(memo) { memo[path] }?.let { hit ->
            val samePass = hit.gen == status.writingGen
            val fresh = !status.running || clock - hit.at < WHILE_CRAWLING_MS
            if (samePass && fresh) return hit.tally
        }
        // On IO because this is reached from `produceState` in a list row, whose coroutine runs
        // on the composition's own context. A database read there is a main-thread read.
        val computed = withContext(Dispatchers.IO) { runCatching { fromIndex(path) }.getOrNull() }
        synchronized(memo) { memo[path] = IndexMemo(computed, status.writingGen, clock) }
        return computed
    }

    /**
     * The live walk for [path], started if nothing is already walking it.
     *
     * @param force walk even a remote folder; set by the sheet's Measure button.
     */
    fun walk(path: VPath, force: Boolean = false): StateFlow<WalkState> = synchronized(walks) {
        val running = walks[path]
        if (running != null && running.job?.isActive == true) return running.walk.asStateFlow()
        if (!force && !FolderSizes.walksOnOpen(runCatching { vfs.isRemote(path) }.getOrDefault(true))) {
            return MutableStateFlow<WalkState>(WalkState.OnRequest).asStateFlow()
        }
        val state = MutableStateFlow<WalkState>(WalkState.Running(FolderTally.EMPTY))
        val entry = Entry(state, null)
        walks[path] = entry
        entry.job = scope.launch { run(path, state) }
        state.asStateFlow()
    }

    private suspend fun run(path: VPath, state: MutableStateFlow<WalkState>) {
        val me = currentCoroutineContext()[Job]
        var row: Long? = null
        val announce = scope.launch {
            delay(LEDGER_AFTER_MS)
            row = ledger.start("Measuring ${path.name.ifEmpty { path.toString() }}", "Counting files and sizes", cancel = me, opened = path.toString())
        }
        try {
            val tally = withContext(Dispatchers.IO) { walkTree(path) { state.value = WalkState.Running(it) } }
            val at = now()
            synchronized(confirmed) { confirmed[path] = CachedTally(tally, TallySource.WALK, at) }
            state.value = WalkState.Done(tally, at)
            row?.let { ledger.finish(it, FolderSizes.contentsLine(tally)) }
        } catch (e: CancellationException) {
            state.value = WalkState.Failed("stopped")
            row?.let { ledger.cancel(it) }
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e::class.java.simpleName
            state.value = WalkState.Failed(why)
            row?.let { ledger.fail(it, why) }
        } finally {
            announce.cancel()
        }
    }

    /**
     * Depth-first through the VFS, with an explicit stack so a deep tree cannot overflow the
     * thread's own.
     *
     * A folder that refuses a listing is counted in [FolderTally.unreadable] and walked past,
     * rather than failing the whole measurement: `Android/data` refuses on every phone since
     * 11, and a sheet that could never measure internal storage because of it would be useless.
     */
    private suspend fun walkTree(root: VPath, onProgress: (FolderTally) -> Unit): FolderTally {
        val b = TallyBuilder()
        val guard = LoopGuard()
        vfs.stat(root)?.let { guard.enter(it.inode, 0) }
        // (folder, its top-level name, depth)
        val stack = ArrayDeque<Triple<VPath, String?, Int>>()
        stack.addLast(Triple(root, null, 0))
        var lastTick = 0L
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, top, depth) = stack.removeLast()
            val children: List<VNode> = try {
                vfs.list(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                b.unreadable()
                continue
            }
            for (c in children) {
                val t = top ?: c.name
                b.add(t, top == null, c.isDir, c.size, c.inode, c.mtime)
                if (c.isDir && guard.enter(c.inode, depth + 1)) stack.addLast(Triple(c.path, t, depth + 1))
            }
            val clock = now()
            if (clock - lastTick >= TICK_MS) {
                lastTick = clock
                onProgress(b.build())
            }
        }
        return b.build()
    }

    private suspend fun fromIndex(path: VPath): CachedTally? {
        val b = TallyBuilder()
        if (!index.subtree(path) { top, direct, isDir, size, inode, mtime -> b.add(top, direct, isDir, size, inode, mtime) }) return null
        val tally = b.build()
        // No files reached under this folder is NOT an empty folder.
        //
        // Bug identified here: the index holds rows only where its crawl has been, so a folder
        // whose contents it has never opened produces a tally of zero - and that was handed out
        // and printed as "0 B", which reads as empty on a row somebody may be about to delete
        // from. On a real phone six folders said 0 B with directories plainly inside them.
        // The index cannot tell "nothing here" from "nothing seen", so it must claim neither;
        // the item count beside the name is what honestly answers emptiness.
        if (tally.files == 0L && tally.bytes == 0L) return null
        val ranAt = index.status.value.lastRunAt
        return CachedTally(tally, TallySource.INDEX, ranAt)
    }

    companion object {
        /** A walk shorter than this never appears in the activity sheet. */
        const val LEDGER_AFTER_MS = 1500L
        const val TICK_MS = 250L
        private const val MEMO = 64

        /**
         * How long an index figure is trusted while a crawl is running.
         *
         * Only while one IS running - between passes the generation key holds the figure
         * indefinitely. Short enough that a row catches up with a pass walking past it, long
         * enough that a fling does not re-aggregate a subtree per row per second, which is the
         * cost the cache exists to remove.
         */
        const val WHILE_CRAWLING_MS = 4_000L

        /**
         * How many folders may be waiting to be walked at once.
         *
         * Bounded so a fling down a folder of hundreds of subfolders cannot build an unbounded
         * backlog that outlives any interest in it. Dropping the overflow is safe because the
         * rows ask again while they are on screen, so what is visible is what gets queued.
         */
        const val QUEUE_MAX = 48

        @Volatile private var instance: FolderMeasure? = null

        /** One per process, built from the graph the first time a sheet asks. */
        fun of(graph: dev.niccc2007.filet.FiletGraph): FolderMeasure =
            instance ?: synchronized(this) {
                instance ?: FolderMeasure(graph.vfs, graph.index, graph.ledger, graph.scope).also { instance = it }
            }
    }
}
