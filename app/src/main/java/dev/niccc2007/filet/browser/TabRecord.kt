package dev.niccc2007.filet.browser

import dev.niccc2007.filet.index.Reach
import dev.niccc2007.filet.index.VolumeKey
import dev.niccc2007.filet.vfs.VPath
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tab as it is saved: everything needed to put it back exactly as it was left.
 *
 * Bug identified here: a tab came back as its kind, its folder and its open folds, and nothing
 * else. Its scroll position, the branch focused in its tree and its search were gone; a category
 * tab came back as an empty pane with no category; and a special tab came back under its enum
 * name, so "Opened" reopened as "Recent" and "New files" as "History".
 *
 * Then a second round of the same fault, one layer out: the tab came back where it was, but
 * only the folder it was IN. Its Back and Forward started empty, every folder behind it had
 * forgotten its scroll, and every screen that is not a folder - Home, a category, Settings, the
 * playlists - opened at its top, on its first sub-page. So [back] and [forward] carry the
 * history, [places] every position the tab had filed, and [kept] each screen's own choice of
 * view (Settings' page, the playlist that was open).
 *
 * Read with [TabCodec.decode], which accepts every record written before these fields existed -
 * a release that cannot read the tabs the last one saved has lost them as surely as one that
 * never saved them.
 */
data class TabRecord(
    val kind: PaneKind,
    val path: VPath? = null,
    /** The pane's own title, for the panes whose wording is not their kind's name. */
    val title: String? = null,
    /** `MediaCategory.id`, for a category tab. */
    val category: String? = null,
    val folds: List<VPath> = emptyList(),
    /** The branch focused in the tree, or null for the tab's own folder. */
    val focus: VPath? = null,
    val scroll: ScrollMemory.Marks? = null,
    val search: SearchRecord? = null,
    /** Every scroll position the tab filed, by place, the one used longest ago first. */
    val places: List<Pair<String, ScrollMemory.Marks>> = emptyList(),
    /** What each screen in the tab keeps about its own view, by the screen's key. See [PaneMemory]. */
    val kept: Map<String, String> = emptyMap(),
    /** The tab's Back history, oldest first. Its entries carry no history of their own. */
    val back: List<TabRecord> = emptyList(),
    /** The tab's Forward history, in the order Forward reaches them last. */
    val forward: List<TabRecord> = emptyList(),
)

/**
 * A history entry and the record it is saved as.
 *
 * The same few facts a tab itself is put back from - kind, folder, title, category, folds,
 * focus - because a step back is a tab being put back where it was a moment ago. Pure, so the
 * round trip is tested rather than trusted: an entry that comes back as the wrong kind sends
 * Back somewhere that was never visited.
 */
object TabHistory {

    /** Entries kept on each side. A long session's worth of Back, at a few hundred bytes each. */
    const val KEEP = 24

    /** Places kept per tab. The folders most recently scrolled in; older ones open at the top. */
    const val PLACES = 40

    fun recordOf(s: PaneState): TabRecord = TabRecord(
        kind = s.kind,
        path = s.cwd,
        title = s.title.takeIf { s.kind != PaneKind.FOLDER && s.kind != PaneKind.HOME },
        category = s.category?.id,
        folds = TreeRows.foldsToSave(s.cwd, s.open),
        focus = s.active,
    )

    /**
     * The state Back would put back, or null for a record this build cannot honour: a folder
     * with no path, a category that no longer exists. Dropped rather than guessed at.
     *
     * @param folderTitle the name a pane gives a folder it is in.
     */
    fun stateOf(r: TabRecord, id: Int, folderTitle: (VPath) -> String): PaneState? = when (r.kind) {
        PaneKind.FOLDER -> r.path?.let { p ->
            PaneState(id = id, kind = PaneKind.FOLDER, cwd = p, title = folderTitle(p), open = r.folds.toSet(), active = r.focus)
        }
        PaneKind.HOME -> PaneState(id = id, kind = PaneKind.HOME, title = "Home")
        PaneKind.CATEGORY -> dev.niccc2007.filet.home.MediaCategory.entries.firstOrNull { it.id == r.category }?.let { c ->
            PaneState(id = id, kind = PaneKind.CATEGORY, category = c, title = c.label)
        }
        else -> PaneState(id = id, kind = r.kind, title = r.title ?: r.kind.name.lowercase().replaceFirstChar { it.uppercase() })
    }
}

/** A search that was open, by what it asked and how far it looked. */
data class SearchRecord(val query: String, val reach: Reach)

object TabCodec {

    fun encode(r: TabRecord): JSONObject {
        val o = JSONObject()
            .put("kind", r.kind.name)
            .put("path", r.path?.toString() ?: "")
        val folds = JSONArray()
        for (f in r.folds) folds.put(f.toString())
        o.put("folds", folds)
        r.title?.let { o.put("title", it) }
        r.category?.let { o.put("category", it) }
        r.focus?.let { o.put("focus", it.toString()) }
        r.scroll?.list?.let { o.put("list", mark(it)) }
        r.scroll?.grid?.let { o.put("grid", mark(it)) }
        r.search?.let { s ->
            o.put("search", JSONObject().put("q", s.query).put("reach", reach(s.reach)))
        }
        if (r.places.isNotEmpty()) {
            val arr = JSONArray()
            for ((place, marks) in r.places) {
                val p = JSONObject().put("p", place)
                marks.list?.let { p.put("list", mark(it)) }
                marks.grid?.let { p.put("grid", mark(it)) }
                arr.put(p)
            }
            o.put("places", arr)
        }
        if (r.kept.isNotEmpty()) {
            val k = JSONObject()
            for ((key, value) in r.kept) k.put(key, value)
            o.put("kept", k)
        }
        if (r.back.isNotEmpty()) o.put("back", JSONArray().also { a -> r.back.forEach { a.put(encode(it)) } })
        if (r.forward.isNotEmpty()) o.put("forward", JSONArray().also { a -> r.forward.forEach { a.put(encode(it)) } })
        return o
    }

    /** Null when the kind is one this build does not have; the caller opens Home there. */
    fun decode(o: JSONObject): TabRecord? {
        val kind = runCatching { PaneKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
        val folds = ArrayList<VPath>()
        o.optJSONArray("folds")?.let { arr ->
            for (j in 0 until arr.length()) path(arr.optString(j))?.let { folds += it }
        }
        val list = o.optJSONObject("list")?.let(::mark)
        val grid = o.optJSONObject("grid")?.let(::mark)
        val places = ArrayList<Pair<String, ScrollMemory.Marks>>()
        o.optJSONArray("places")?.let { arr ->
            for (j in 0 until arr.length()) {
                val p = arr.optJSONObject(j) ?: continue
                val place = p.optString("p", "").ifEmpty { null } ?: continue
                val l = p.optJSONObject("list")?.let(::mark)
                val g = p.optJSONObject("grid")?.let(::mark)
                if (l != null || g != null) places += place to ScrollMemory.Marks(l, g)
            }
        }
        val kept = LinkedHashMap<String, String>()
        o.optJSONObject("kept")?.let { k -> for (key in k.keys()) kept[key] = k.optString(key) }
        // A history entry that this build cannot read is skipped, not the whole history.
        fun history(name: String): List<TabRecord> {
            val arr = o.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { j -> arr.optJSONObject(j)?.let(::decode) }
        }
        return TabRecord(
            kind = kind,
            path = path(o.optString("path", "")),
            title = o.optString("title", "").ifEmpty { null },
            category = o.optString("category", "").ifEmpty { null },
            folds = folds,
            focus = path(o.optString("focus", "")),
            scroll = if (list == null && grid == null) null else ScrollMemory.Marks(list, grid),
            search = o.optJSONObject("search")?.let { s ->
                val q = s.optString("q", "")
                val reach = s.optJSONObject("reach")?.let(::reach)
                if (q.isBlank() || reach == null) null else SearchRecord(q, reach)
            },
            places = places,
            kept = kept,
            back = history("back"),
            forward = history("forward"),
        )
    }

    private fun path(raw: String?): VPath? =
        raw?.takeIf { it.isNotEmpty() }?.let { runCatching { VPath.parse(it) }.getOrNull() }

    private fun mark(m: ScrollMemory.Mark): JSONObject =
        JSONObject().put("i", m.index).put("o", m.offset).also { o -> m.key?.let { o.put("k", it) } }

    private fun mark(o: JSONObject): ScrollMemory.Mark? {
        val i = o.optInt("i", -1)
        if (i < 0) return null
        return ScrollMemory.Mark(i, o.optInt("o", 0).coerceAtLeast(0), o.optString("k", "").ifEmpty { null })
    }

    private fun reach(r: Reach): JSONObject = when (r) {
        Reach.Folder -> JSONObject().put("t", "folder")
        Reach.Subtree -> JSONObject().put("t", "subtree")
        Reach.Everywhere -> JSONObject().put("t", "everywhere")
        is Reach.Drives -> JSONObject().put("t", "drives").put(
            "roots",
            JSONArray().also { a -> r.keys.forEach { a.put(it.root.toString()) } },
        )
    }

    private fun reach(o: JSONObject): Reach? = when (o.optString("t")) {
        "folder" -> Reach.Folder
        "subtree" -> Reach.Subtree
        "everywhere" -> Reach.Everywhere
        "drives" -> {
            val keys = LinkedHashSet<VolumeKey>()
            o.optJSONArray("roots")?.let { a ->
                for (j in 0 until a.length()) path(a.optString(j))?.let { keys += VolumeKey(it) }
            }
            Reach.Drives(keys)
        }
        else -> null
    }
}
