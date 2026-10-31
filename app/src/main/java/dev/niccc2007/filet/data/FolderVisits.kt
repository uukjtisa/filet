package dev.niccc2007.filet.data

import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * How often each folder has been used, for the side bar's Quick access.
 *
 * Separate from [Recents] because the two answer different questions. Recents is the Opened
 * list: what was opened, files and folders, newest first. This counts folders IN USE, which
 * includes the folder a file was opened from - and writing those into Recents would put a row
 * for `Camera` into the Opened list every time a photo in it was looked at. What counts as a
 * visit is `QuickAccess.creditFor`.
 *
 * @param seed the folders already in the Opened list, read once when nothing has been stored
 *   yet, so the counts a phone had built up before this existed are not thrown away.
 */
class FolderVisits(private val prefs: Prefs, seed: () -> List<RecentEntry>) {

    private val _items = MutableStateFlow(load() ?: seed().filter { it.isDir }.take(CAP))
    val items: StateFlow<List<RecentEntry>> = _items.asStateFlow()

    /** One more visit to [folder]. */
    fun credit(folder: VPath, label: String = folder.name) {
        val next = bump(_items.value, folder, label, System.currentTimeMillis())
        _items.value = next
        save(next)
    }

    private fun load(): List<RecentEntry>? {
        val raw = prefs.getString(KEY) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val p = runCatching { VPath.parse(o.getString("path")) }.getOrNull() ?: return@mapNotNull null
                RecentEntry(p, o.optString("label", p.name), o.optLong("at"), isDir = true, visits = o.optInt("visits", 1))
            }
        }.getOrElse { emptyList() }
    }

    private fun save(items: List<RecentEntry>) {
        val arr = JSONArray()
        for (r in items) {
            arr.put(JSONObject().put("path", r.path.toString()).put("label", r.label).put("at", r.at).put("visits", r.visits))
        }
        prefs.putString(KEY, arr.toString())
    }

    companion object {
        private const val KEY = "folderVisits"

        /**
         * How many folders are remembered. Kept by recency, which loses nothing that matters:
         * a folder used often is by definition one used lately.
         */
        const val CAP = 60

        /** [visits] with one more visit to [folder], newest first, at most [cap] long. */
        fun bump(visits: List<RecentEntry>, folder: VPath, label: String, now: Long, cap: Int = CAP): List<RecentEntry> {
            val was = visits.firstOrNull { it.path == folder }
            val entry = RecentEntry(folder, label, now, isDir = true, visits = (was?.visits ?: 0) + 1)
            return (listOf(entry) + visits.filterNot { it.path == folder }).take(cap)
        }
    }
}
