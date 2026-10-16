package dev.niccc2007.filet.home

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last feed that was shown, so the next cold start has something to draw immediately.
 *
 * ## Why this exists
 *
 * Reported on a full 256GB phone: New files showed nothing for two to five seconds, then
 * yesterday's files, then today's. The last part is a scan finishing and is not avoidable -
 * reading what is in a folder takes as long as it takes. The first part was: the answer from
 * the previous pass was already known and was being thrown away at every process start.
 *
 * So the feed is written down when a pass completes and read back when the app starts. The
 * first frame is last night's answer rather than an empty list, the scan replaces it when it
 * has something better, and because it is the same list in the same order the rows that are
 * still there do not move.
 *
 * ## What is NOT stored, and why
 *
 * No provenance, and no size. Both are resolved per visible row at display time and both can
 * be wrong by morning; a cache that carries them would be a cache that lies in more ways
 * than one. The path, the kind and the two timestamps are enough to draw a row, and every one
 * of them is checked again by the pass that is already running when the row is on screen.
 *
 * A cached row may point at a file that has since been deleted. That is the same situation as
 * any list in a file manager a second after it is drawn, and it is handled the same way: the
 * row is replaced when the pass answers.
 */
class FeedCache(private val prefs: Prefs) {

    fun load(): List<FeedItem> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val out = ArrayList<FeedItem>()
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val path = VPath.parse(o.getString("p"))
                out.add(
                    FeedItem(
                        node = VNode(
                            path = path,
                            isDir = o.optBoolean("d", false),
                            size = o.optLong("s", -1L),
                            mtime = o.optLong("m", 0L),
                        ),
                        at = o.optLong("a", 0L),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(items: List<FeedItem>) {
        // Capped at what the card and the first screen of the tab can show. The expanded list
        // is a scan away and is not worth carrying thousands of rows of JSON for.
        val array = JSONArray()
        for (item in items.take(CAP)) {
            array.put(
                JSONObject()
                    .put("p", item.node.path.toString())
                    .put("d", item.node.isDir)
                    .put("s", item.node.size)
                    .put("m", item.node.mtime)
                    .put("a", item.at),
            )
        }
        prefs.putString(KEY, array.toString())
    }

    private companion object {
        const val KEY = "home.feed.v1"

        /** How many rows are kept. The home card shows a handful; the tab shows a screen. */
        const val CAP = 40
    }
}
