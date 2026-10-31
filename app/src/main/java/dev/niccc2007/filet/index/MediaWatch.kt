package dev.niccc2007.filet.index

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.MediaChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The index kept current from the media store, while Filet is in use and for a while after.
 * SEARCH.md §14.6.
 *
 * ## What this is not
 *
 * It is not the retired Tier 2 (`IndexCoordinator.rearmMediaTrigger`). That was a JobScheduler
 * trigger, so the system woke the app for every media change on the phone, and each wake was a
 * twenty-second walk of every root. This is an observer registered inside the running process:
 * it hears nothing once the process is gone, nothing can wake the app for it, and what it does
 * with a change is read the folder the change happened in.
 *
 * ## Three moments
 *
 * - **In the foreground**, every change, coalesced over [MediaDelta.SETTLE_MS].
 * - **After a minimise**, the same, for exactly the minutes in Settings, then released.
 * - **Coming back**, one generation query: whatever changed while Filet was away.
 */
class MediaWatch(
    private val context: Context,
    private val index: FileIndex,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** The bounded pass, for a delta too large or a media store that was rebuilt. */
    private val fallback: () -> Unit,
) {
    private val sp = context.getSharedPreferences("filet.mediawatch", Context.MODE_PRIVATE)
    private var handle: AutoCloseable? = null
    private var release: Job? = null
    private var pending: Job? = null
    /** One catch-up at a time: two would read the same delta and store the generation twice. */
    private val reading = Mutex()

    /** Whether the listener is wanted at all: an index, kept up to date on its own. */
    private fun wanted(): Boolean = prefs.indexEnabled.value && prefs.indexAuto.value && MediaChanges.hasGenerations

    /** The first activity started. Cancels a pending release and reads what changed meanwhile. */
    fun onForeground() {
        release?.cancel()
        release = null
        if (!wanted()) {
            stop()
            return
        }
        if (handle == null) handle = MediaChanges.observe(context) { schedule() }
        schedule(settle = 0L)
    }

    /**
     * The last activity stopped. The listener stays for the window chosen in Settings, read now,
     * and is then released - not earlier on quiet, not later on activity (decision 20).
     */
    fun onBackground() {
        val minutes = ListenWindow.normalise(prefs.listenMinutes.value)
        if (minutes == 0 || !wanted()) {
            stop()
            return
        }
        release?.cancel()
        val at = System.currentTimeMillis()
        release = scope.launch {
            delay(ListenWindow.releaseAt(at, minutes) - at)
            stop()
        }
    }

    /** Release everything now: the window ran out, or the index was turned off or set to Manual. */
    fun stop() {
        pending?.cancel()
        pending = null
        handle?.close()
        handle = null
    }

    /**
     * Coalesce: a new change restarts the wait, never a read already under way.
     *
     * Bug identified in review: the read was inside the job this cancels, so in a burst of
     * changes a catch-up was routinely cancelled half way through its folders - and the
     * cancellation was swallowed and the generation recorded as read, so every folder after the
     * cut was never read at all. The wait is cancellable; the read it starts is its own job.
     */
    private fun schedule(settle: Long = MediaDelta.SETTLE_MS) {
        pending?.cancel()
        pending = scope.launch {
            delay(settle)
            scope.launch { catchUp() }
        }
    }

    /** Read the folders of every row changed since the last look, per volume. */
    suspend fun catchUp() = reading.withLock {
        if (!wanted()) return@withLock
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            for ((volume, current) in MediaChanges.generations(context)) {
                val key = "gen." + volume
                val stored = sp.getLong(key, -1L)
                when (MediaDelta.plan(stored, current)) {
                    MediaDelta.Plan.NOTHING -> continue
                    MediaDelta.Plan.BASELINE -> Unit
                    MediaDelta.Plan.RESET -> fallback()
                    MediaDelta.Plan.DELTA -> {
                        // A failed query is not "nothing changed": the generation is left where
                        // it was, so the next look asks again.
                        val changed = MediaChanges.changedSince(context, volume, stored) ?: continue
                        val folders = MediaDelta.folders(changed)
                        if (folders == null) {
                            fallback()
                        } else if (folders.isNotEmpty()) {
                            // Cancellation propagates: the generation below must not be written
                            // for a read that did not finish. Anything else that goes wrong is
                            // the index's, and is the bounded pass's to repair.
                            try {
                                index.refreshFolders(folders.map { VPath.of("local", it) })
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                fallback()
                            }
                        }
                    }
                }
                // Written after the read, so a read that was killed is repeated rather than lost.
                sp.edit().putLong(key, current).apply()
            }
        }
    }

    /**
     * A first build from the media store's own table, for an index that holds nothing.
     *
     * One query - eleven thousand rows in a few seconds on the phone this was measured on -
     * against minutes of walking. The rows are added for the drives that have never completed a
     * pass and nothing else; the bounded passes then correct whatever the media store had wrong.
     */
    suspend fun seed(roots: List<VPath>): Int {
        if (!prefs.indexEnabled.value) return 0
        val local = roots.filter { it.scheme == "local" }
        if (local.isEmpty()) return 0
        // The generation is taken BEFORE the read, so a change landing during it is in the next
        // delta rather than in neither.
        val gens = MediaChanges.generations(context)
        val rows = kotlinx.coroutines.withContext(Dispatchers.IO) { MediaChanges.everything(context) }
        val added = runCatching {
            index.seed(local, rows.map { SeedEntry(VPath.of("local", it.osPath), it.isDir, it.size, it.mtime) })
        }.getOrDefault(0)
        for ((volume, g) in gens) if (sp.getLong("gen." + volume, -1L) < 0) sp.edit().putLong("gen." + volume, g).apply()
        return added
    }
}
