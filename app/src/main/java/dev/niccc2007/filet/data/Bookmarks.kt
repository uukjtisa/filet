package dev.niccc2007.filet.data

import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val label: String, val path: VPath)

/**
 * Starred locations, shown in the rail beside the real volumes.
 *
 * Stored as JSON in [Prefs] rather than in a table: this is a list of a dozen strings that is
 * read once at startup, and a database for it would be ceremony. It moves into the index
 * only if bookmarks ever need to be ID-resolved the way pinned shortcuts are.
 */
class Bookmarks(private val prefs: Prefs) {

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    fun add(path: VPath, label: String = path.name.ifEmpty { path.scheme }) {
        if (_items.value.any { it.path == path }) return
        _items.value = _items.value + Bookmark(label, path)
        save()
    }

    fun remove(path: VPath) {
        _items.value = _items.value.filterNot { it.path == path }
        save()
    }

    fun rename(path: VPath, label: String) {
        _items.value = _items.value.map { if (it.path == path) it.copy(label = label) else it }
        save()
    }

    fun toggle(path: VPath) = if (contains(path)) remove(path) else add(path)

    fun contains(path: VPath): Boolean = _items.value.any { it.path == path }

    fun move(from: Int, to: Int) {
        val list = _items.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _items.value = list
        save()
    }

    private fun load(): List<Bookmark> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val p = runCatching { VPath.parse(o.getString("path")) }.getOrNull() ?: return@mapNotNull null
                Bookmark(o.optString("label", p.name), p)
            }
        }.getOrElse { emptyList() }   // a corrupt blob loses bookmarks, never the app
    }

    private fun save() {
        val arr = JSONArray()
        for (b in _items.value) {
            arr.put(JSONObject().put("label", b.label).put("path", b.path.toString()))
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object { const val KEY = "bookmarks" }
}

/** One entry in the "opened recently" list on the Home overview. */
data class RecentEntry(val path: VPath, val label: String, val at: Long, val isDir: Boolean)

/**
 * A short MRU list, capped and de-duplicated by path.
 *
 * Separate from the index's frecency table on purpose: this survives with no index at all,
 * which matters because Home is the first screen after onboarding and must never be empty
 * just because a crawl has not run yet.
 */
class Recents(private val prefs: Prefs) {

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentEntry>> = _items.asStateFlow()

    fun record(path: VPath, isDir: Boolean, label: String = path.name) {
        val now = System.currentTimeMillis()
        val next = (listOf(RecentEntry(path, label, now, isDir)) +
            _items.value.filterNot { it.path == path }).take(CAP)
        _items.value = next
        save(next)
    }

    /** Drop one entry — used when a Home row turns out to point at a file that is gone. */
    fun remove(path: VPath) {
        val next = _items.value.filterNot { it.path == path }
        _items.value = next
        save(next)
    }

    fun clear() {
        _items.value = emptyList()
        prefs.putString(KEY, null)
    }

    private fun load(): List<RecentEntry> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val p = runCatching { VPath.parse(o.getString("path")) }.getOrNull() ?: return@mapNotNull null
                RecentEntry(p, o.optString("label", p.name), o.optLong("at"), o.optBoolean("dir"))
            }
        }.getOrElse { emptyList() }
    }

    private fun save(items: List<RecentEntry>) {
        val arr = JSONArray()
        for (r in items) {
            arr.put(
                JSONObject().put("path", r.path.toString()).put("label", r.label)
                    .put("at", r.at).put("dir", r.isDir)
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object {
        const val KEY = "recents"
        const val CAP = 40
    }
}
