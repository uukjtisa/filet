package dev.niccc2007.filet.home

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * A row on the Home overview: a file, when it appeared, and where it came from.
 *
 * [origin] is the provenance chip (PLAN.md §5.1) and stays null until something has written a
 * biography record. Rendering "unknown" there would be worse than rendering nothing.
 */
data class FeedItem(val node: VNode, val at: Long, val origin: String? = null)

/**
 * Tracked folders: the places worth noticing changes in.
 *
 * Download is tracked by default because it is where things arrive. This is deliberately a
 * *short* list: it is also the watch list for the inotify tier (SEARCH.md §4.1), and inotify
 * costs one watch per directory against a per-process ceiling of roughly 8192.
 */
/**
 * One tracked place.
 *
 * @param recursive watch everything beneath it too. Off by default, and deliberately not the
 *   default: the tracked list is also the inotify watch list, inotify costs one watch per
 *   DIRECTORY, and the per-process ceiling is about 8192. Tracking `/storage/emulated/0`
 *   recursively would spend the whole budget on one entry. Nic asked for it because adding
 *   folders one at a time is a hassle, which is true, so it is offered with the cost named
 *   rather than hidden.
 */
data class TrackedFolder(val path: VPath, val recursive: Boolean = false)

class TrackedFolders(private val prefs: Prefs, private val scope: CoroutineScope) {

    private val _folders = MutableStateFlow(load())
    val folders: StateFlow<List<TrackedFolder>> = _folders.asStateFlow()

    /**
     * Just the paths, for the callers that only ever wanted those.
     *
     * One flow, mapped once. Building it per read would hand every collector a flow that
     * never emits again, which is the quiet kind of broken: the first frame looks right and
     * nothing updates afterwards.
     */
    val paths: StateFlow<List<VPath>> = _folders
        .map { list -> list.map { it.path } }
        .stateIn(scope, SharingStarted.Eagerly, _folders.value.map { it.path })

    fun add(p: VPath, recursive: Boolean = false) {
        if (_folders.value.any { it.path == p }) return
        _folders.value = _folders.value + TrackedFolder(p, recursive)
        save()
    }

    fun remove(p: VPath) {
        _folders.value = _folders.value.filterNot { it.path == p }
        save()
    }

    /** Turn subfolder tracking on or off for one entry, without losing its place in the list. */
    fun setRecursive(p: VPath, recursive: Boolean) {
        _folders.value = _folders.value.map { if (it.path == p) it.copy(recursive = recursive) else it }
        save()
    }

    fun contains(p: VPath) = _folders.value.any { it.path == p }

    fun isRecursive(p: VPath) = _folders.value.firstOrNull { it.path == p }?.recursive == true

    /**
     * Seed with the conventional download directory the first time the app runs.
     *
     * **Local volumes only**, and that is a bug fix rather than a preference. This used to seed
     * `Download` under EVERY root, which on his phone meant the root provider, `/system`, and a
     * WebDAV test server that is not running. The feed then waited on that server's connect
     * timeout on every refresh, so a screenshot taken a foot away took two minutes to appear.
     * A remote share is a fine thing to track; it is not a sensible thing to track by default,
     * because nobody asked for it and nobody will think to remove it.
     */
    fun seedDefaults(roots: List<VPath>) {
        if (prefs.getBool(SEEDED, false)) return
        prefs.putBool(SEEDED, true)
        for (r in roots) {
            if (r.scheme !in SEEDABLE_SCHEMES) continue
            _folders.value = _folders.value + TrackedFolder(r.child("Download"))
        }
        save()
    }

    /**
     * Drop the remote and root entries the old seeding added, once.
     *
     * Only entries that look exactly like what the seeder made - a folder called `Download` on
     * a scheme it should never have touched. Anything the user added by hand survives, whatever
     * its scheme, because removing somebody's tracked folder without being asked is worse than
     * leaving a slow one in the list. The timeout in `HomeFeed.refresh` already stops it hurting.
     */
    fun pruneSeededRemotes() {
        if (prefs.getBool(PRUNED, false)) return
        prefs.putBool(PRUNED, true)
        val before = _folders.value
        val after = before.filterNot {
            it.path.scheme !in SEEDABLE_SCHEMES && it.path.path.trimEnd('/').endsWith("/Download")
        }
        if (after.size != before.size) {
            _folders.value = after
            save()
        }
    }

    /**
     * Reads both shapes.
     *
     * Entries written before subfolder tracking existed are bare strings; new ones are
     * objects. Migrating on read rather than on upgrade means a downgrade does not lose the
     * list, and it costs one type check.
     */
    private fun load(): List<TrackedFolder> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val entry = arr.get(i)
                runCatching {
                    if (entry is org.json.JSONObject) {
                        TrackedFolder(VPath.parse(entry.getString("path")), entry.optBoolean("deep", false))
                    } else {
                        TrackedFolder(VPath.parse(entry.toString()), recursive = false)
                    }
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun save() {
        val arr = JSONArray()
        _folders.value.forEach {
            arr.put(org.json.JSONObject().put("path", it.path.toString()).put("deep", it.recursive))
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object {
        const val KEY = "tracked.folders"
        const val SEEDED = "tracked.seeded"
        const val PRUNED = "tracked.prunedRemotes"

        /**
         * Where a default is allowed to be seeded.
         *
         * `local` is the device's own storage; `saf` is a folder the user granted, which they
         * chose, so a Download inside one is a reasonable guess. Everything else - remotes,
         * root, archives - is somewhere a default has no business being.
         */
        val SEEDABLE_SCHEMES = setOf("local", "saf")
    }
}

/**
 * What Home shows above the fold.
 *
 * Reads the tracked folders directly through the VFS rather than waiting for an index, so the
 * first screen after onboarding is populated on a device that has never been crawled. M3
 * replaces the polling refresh with watcher pushes; the shape of the data does not change.
 */
class HomeFeed(
    private val vfs: Vfs,
    private val tracked: TrackedFolders,
    private val scope: CoroutineScope,
) {
    private val _downloads = MutableStateFlow<List<FeedItem>>(emptyList())
    val downloads: StateFlow<List<FeedItem>> = _downloads.asStateFlow()

    /**
     * Everything the last pass found, untrimmed.
     *
     * The expanded history is this list; the home card is the first [SHOWN] of it. Kept as one
     * value rather than a second scan, which was Nic's own point when the feature was
     * specified - there is already an architecture for what is being watched, and a second
     * scanner would drift from the feed and disagree with it about what is new.
     *
     * Provenance is deliberately NOT resolved for these. It costs a lookup per file and the
     * expanded list can hold thousands; the visible rows get it, the rest do not need it.
     */
    private val _all = MutableStateFlow<List<FeedItem>>(emptyList())
    val all: StateFlow<List<FeedItem>> = _all.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Supplies the Source chip once provenance exists. Set by the bridge at M7. */
    var originLookup: (suspend (VPath) -> String?)? = null

    /** The last time a change arrived, and the first one of the current burst. */
    private var burstStartedAt = 0L
    private var lastChangeAt = 0L
    private var pending: Job? = null

    /**
     * Something changed in a watched folder.
     *
     * Coalesced rather than serviced per event: copying twenty files in fires twenty
     * notifications, and twenty full re-reads of every tracked folder is exactly why the feed
     * felt slow. See [coalesceDelay] for why there are two clocks.
     */
    fun onChanged(changed: VPath? = null, name: String? = null) {
        // THE FAST PATH, and the reason the feed is instant rather than eventually right.
        //
        // His requirement: *"it needs to be blazing instant.. thats our goal for the tracker"*.
        //
        // The kernel tells us the FILE that changed, not just the folder, and that distinction
        // is the whole thing. His Screenshots folder holds 1700 files; listing it through the
        // VFS takes seconds, so any design that re-reads a directory to notice one new file is
        // slow in exactly the case that matters. One `stat` of one known path is microseconds
        // and does not care how big the folder is.
        //
        // The coalesced full pass below still runs, and still reconciles deletions and anything
        // the watcher did not report. It just is not what anybody waits for.
        if (changed != null) scope.launch { spliceNow(changed, name) }

        val now = System.currentTimeMillis()
        if (pending?.isActive != true) burstStartedAt = now
        lastChangeAt = now
        if (pending?.isActive == true) return
        pending = scope.launch {
            while (true) {
                val wait = coalesceDelay(
                    sinceFirstChange = System.currentTimeMillis() - burstStartedAt,
                    sinceLastChange = System.currentTimeMillis() - lastChangeAt,
                )
                if (wait <= 0L) break
                delay(wait)
            }
            refresh()
        }
    }

    private var running: Job? = null

    /**
     * The last complete read of each tracked folder.
     *
     * So a folder that times out on one pass shows what it showed before, rather than
     * disappearing from the feed and reappearing on the next pass.
     */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<VPath, List<FeedItem>>()

    /**
     * Re-read every tracked folder.
     *
     * Nic: *"the screenshot finally showed up.. 2mins ago though.. so its not realtime"*, and
     * separately that the refresh button did nothing on Home. Those were one bug.
     *
     * The old version read the folders in parallel and then `awaitAll`ed the lot before
     * publishing anything. His tracked list had been seeded with a Download folder on EVERY
     * volume, including a WebDAV test server that is not running - so every refresh sat on that
     * connect timeout, and a screenshot taken a foot away could not appear until a dead remote
     * on another network finished failing. Refresh had the same problem, which is why pressing
     * it looked like it did nothing.
     *
     * Two changes, and the first is the one that matters:
     *
     * 1. **Each folder gets its own deadline.** A tracked place that cannot answer within
     *    [FOLDER_TIMEOUT_MS] is left out of this pass rather than holding up the others. The
     *    next refresh tries again; nothing is forgotten, it is just not waited for.
     * 2. **Results publish as they arrive.** The local folders are back in milliseconds, so the
     *    feed is right almost immediately and only gets more right as slower places answer.
     *    Waiting for the slowest folder before showing the fastest one was the whole defect.
     */
    fun refresh() {
        // One pass at a time. Pressing refresh during a slow pass should replace it, not race
        // it: two passes finishing out of order would publish the older answer last.
        running?.cancel()
        running = scope.launch {
            _loading.value = true
            val folders = tracked.folders.value
            if (folders.isEmpty()) {
                _downloads.value = emptyList()
                _loading.value = false
                return@launch
            }
            val gathered = java.util.concurrent.ConcurrentHashMap<VPath, List<FeedItem>>()
            coroutineScope {
                for (batch in folders.chunked(FEED_PARALLELISM)) {
                    batch.map { folder ->
                        async {
                            val out = ArrayList<FeedItem>()
                            // The deadline wraps the LISTING, not the whole pass.
                            val finished = withTimeoutOrNull(FOLDER_TIMEOUT_MS) {
                                collect(folder.path, folder.recursive, depth = 0, into = out)
                                true
                            } == true
                            // A folder that ran out of time keeps whatever it last contributed
                            // instead of contributing nothing. This is not defensive coding, it
                            // is a bug that already happened: `collect` wraps its listing in
                            // runCatching, which SWALLOWS the timeout's cancellation, so a slow
                            // folder returned an empty list that looked like a successful read
                            // of an empty directory - and the feed went blank.
                            if (finished || out.isNotEmpty()) {
                                lastGood[folder.path] = out
                                gathered[folder.path] = out
                            } else {
                                gathered[folder.path] = lastGood[folder.path].orEmpty()
                            }
                            publish(gathered.values)
                        }
                    }.awaitAll()
                }
            }
            // Deliberately NOT in a `finally`. This pass cancels the previous one, and a
            // finally runs on cancellation too - so publishing there emptied the feed every
            // time Home was reopened, because the new pass killed the old one before it had
            // gathered anything. Cancellation must leave whatever is on screen alone; only a
            // pass that actually finished has a better answer.
            publish(gathered.values)
            _loading.value = false
        }
    }

    /**
     * Sort, trim, and only then ask where these few files came from.
     *
     * The order is the point. Resolving provenance during the walk meant one bridge lookup per
     * candidate - 1700 of them for one folder of screenshots - to produce a list of [SHOWN]
     * rows. Trimming first turns that into at most eight lookups, and it is why the feed went
     * from minutes to immediate.
     */
    private fun publish(all: Collection<List<FeedItem>>) {
        val everything = all.flatten().distinctBy { it.node.path }.sortedByDescending { it.at }
        _all.value = everything
        val top = everything.take(SHOWN)
        _downloads.value = top
        val lookup = originLookup ?: return
        // Off the critical path: the rows are already on screen, and the Source chip fills in
        // a moment later rather than holding everything up.
        scope.launch {
            val withOrigin = top.map { it.copy(origin = runCatching { lookup(it.node.path) }.getOrNull()) }
            // Only if nothing else has published since, or a slow lookup would resurrect a
            // stale list over a newer one.
            if (_downloads.value == top) _downloads.value = withOrigin
        }
    }

    /**
     * One directory, read now, merged into what is already showing.
     *
     * Deliberately not recursive: this is the directory the kernel just told us about, and
     * walking beneath it would turn the fast path back into the slow one.
     *
     * Merged rather than replacing, and de-duplicated by path, because the feed is a view over
     * several folders and this only knows about one of them. The result is the same list the
     * full pass would produce for these rows, arriving sooner - if the full pass later disagrees
     * it wins, which is why it still runs.
     */
    private suspend fun spliceNow(dir: VPath, name: String?) {
        val fresh = ArrayList<FeedItem>()
        if (name != null) {
            // One file, one stat. Independent of how many siblings it has.
            val node = runCatching { vfs.stat(dir.child(name)) }.getOrNull()
            if (node == null || node.isDir || node.hidden) return
            fresh += FeedItem(node, node.mtime, origin = null)
        } else {
            // The kernel did not name it - rare, and worth a listing rather than nothing.
            withTimeoutOrNull(FOLDER_TIMEOUT_MS) {
                collect(dir, recursive = false, depth = 0, into = fresh)
            }
        }
        if (fresh.isEmpty()) return
        publish(listOf(fresh + _downloads.value))
    }

    /**
     * Newly-arrived FILES in one tracked folder.
     *
     * Files only, and that is a reversal of round 7 worth recording. Nic asked then for folders
     * to be detected too; he asked now for the opposite, and he is right the second time.
     *
     * A folder's mtime moves whenever anything inside it changes. So subfolders of a tracked
     * place churn constantly, every one of them claims to be new, and in a list of [SHOWN] rows
     * they push out the actual arrivals. What he originally wanted - noticing a whole extracted
     * archive, or a folder pushed over Nearby - is served better by tracking that place
     * recursively, which lists the files that arrived rather than the box they came in.
     *
     * @param depth bounded, because a tracked root plus recursion is otherwise a full crawl on
     *   every Home refresh, and Home is the first screen after launch.
     */
    private suspend fun collect(dir: VPath, recursive: Boolean, depth: Int, into: MutableList<FeedItem>) {
        val kids = runCatching { vfs.list(dir) }.getOrNull() ?: return
        for (k in kids) {
            if (k.hidden) continue
            if (k.isDir) {
                // Descend when asked, but never list the folder itself as an arrival. A folder
                // that is separately tracked is skipped so its files are not collected twice.
                if (recursive && depth < MAX_DEPTH && !tracked.contains(k.path)) {
                    collect(k.path, true, depth + 1, into)
                }
                continue
            }
            // NO provenance lookup here. That is the whole difference between this being
            // instant and taking minutes: his Screenshots folder holds 1700 files, and asking
            // the bridge where each one came from is 1700 suspend calls to build a list that
            // shows eight rows. Origins are resolved in `publish`, for the rows that survive.
            into += FeedItem(k, k.mtime, origin = null)
        }
    }

    private companion object {
        const val SHOWN = 8

        /**
         * How long one tracked folder gets before this pass gives up on it.
         *
         * This is for the BACKGROUND pass, and it is deliberately generous: a real folder on
         * this phone holds 1700 screenshots and listing it through the VFS is seconds, not
         * milliseconds. 2.5 seconds was tried first and was worse than no timeout - it killed
         * the listing of the one folder the user actually cared about.
         *
         * Nothing waits on this any more. A new file reaches the feed through the watcher's
         * single stat; this deadline only stops an unreachable remote from stalling a pass.
         */
const val FOLDER_TIMEOUT_MS = 12_000L
        /** Deep enough for `Download/Telegram/x`, shallow enough not to be a crawl. */
        const val MAX_DEPTH = 3
    }
}
