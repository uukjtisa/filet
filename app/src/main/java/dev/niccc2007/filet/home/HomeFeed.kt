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

    /** Seed with the conventional download directory the first time the app runs. */
    fun seedDefaults(roots: List<VPath>) {
        if (prefs.getBool(SEEDED, false)) return
        prefs.putBool(SEEDED, true)
        for (r in roots) {
            _folders.value = _folders.value + TrackedFolder(r.child("Download"))
        }
        save()
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
    fun onChanged() {
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

    fun refresh() {
        scope.launch {
            _loading.value = true
            // Read the tracked folders at the same time rather than one after another. Twelve
            // tracked folders on an SD card used to be twelve round trips end to end, and the
            // feed is the first thing on the first screen.
            val folders = tracked.folders.value
            val items = coroutineScope {
                folders.chunked(FEED_PARALLELISM).flatMap { batch ->
                    batch.map { folder ->
                        async {
                            val out = ArrayList<FeedItem>()
                            collect(folder.path, folder.recursive, depth = 0, into = out)
                            out
                        }
                    }.awaitAll()
                }.flatten()
            }
            _downloads.value = items.sortedByDescending { it.at }.take(SHOWN)
            _loading.value = false
        }
    }

    /**
     * Newly-arrived things in one tracked folder.
     *
     * Folders count. They used to be skipped outright, which is why a whole extracted archive
     * or a folder pushed over Nearby left no trace on Home - the row said "new downloads" and
     * a folder is not a download. A folder that is itself tracked is not listed inside its
     * parent, because it has its own row and two of the same thing reads as a bug.
     *
     * @param depth bounded, because a tracked root plus recursion is otherwise a full crawl on
     *   every Home refresh, and Home is the first screen after launch.
     */
    private suspend fun collect(dir: VPath, recursive: Boolean, depth: Int, into: MutableList<FeedItem>) {
        val kids = runCatching { vfs.list(dir) }.getOrNull() ?: return
        for (k in kids) {
            if (k.hidden) continue
            if (k.isDir && tracked.contains(k.path)) continue
            into += FeedItem(k, k.mtime, originLookup?.invoke(k.path))
            if (recursive && k.isDir && depth < MAX_DEPTH) {
                collect(k.path, true, depth + 1, into)
            }
        }
    }

    private companion object {
        const val SHOWN = 8
        /** Deep enough for `Download/Telegram/x`, shallow enough not to be a crawl. */
        const val MAX_DEPTH = 3
    }
}
