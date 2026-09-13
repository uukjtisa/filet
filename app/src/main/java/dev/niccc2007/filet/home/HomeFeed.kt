package dev.niccc2007.filet.home

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class TrackedFolders(private val prefs: Prefs) {

    private val _paths = MutableStateFlow(load())
    val paths: StateFlow<List<VPath>> = _paths.asStateFlow()

    fun add(p: VPath) {
        if (p in _paths.value) return
        _paths.value = _paths.value + p
        save()
    }

    fun remove(p: VPath) {
        _paths.value = _paths.value - p
        save()
    }

    fun contains(p: VPath) = p in _paths.value

    /** Seed with the conventional download directory the first time the app runs. */
    fun seedDefaults(roots: List<VPath>) {
        if (prefs.getBool(SEEDED, false)) return
        prefs.putBool(SEEDED, true)
        for (r in roots) {
            val dl = r.child("Download")
            _paths.value = _paths.value + dl
        }
        save()
    }

    private fun load(): List<VPath> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { VPath.parse(arr.getString(it)) }.getOrNull() }
        }.getOrElse { emptyList() }
    }

    private fun save() {
        val arr = JSONArray()
        _paths.value.forEach { arr.put(it.toString()) }
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

    fun refresh() {
        scope.launch {
            _loading.value = true
            val items = ArrayList<FeedItem>()
            for (dir in tracked.paths.value) {
                val kids = runCatching { vfs.list(dir) }.getOrNull() ?: continue
                for (k in kids) {
                    if (k.isDir || k.hidden) continue
                    items += FeedItem(k, k.mtime, originLookup?.invoke(k.path))
                }
            }
            _downloads.value = items.sortedByDescending { it.at }.take(8)
            _loading.value = false
        }
    }
}
