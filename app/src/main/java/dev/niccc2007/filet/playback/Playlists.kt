package dev.niccc2007.filet.playback

import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A kept, ordered list of tracks.
 *
 * ## Why this exists, and why it is not a music library
 *
 * The player could only ever play a folder. That is the right default - it is a file manager,
 * and the folder IS the album most of the time - but it leaves two things impossible: a queue
 * made of tracks from several places, and a way to keep one.
 *
 * It also left a dead control. The heart on the player screen wrote into Bookmarks, which is
 * a list of *places* shown next to folders and shortcuts, so hearting a song put a file in a
 * list you cannot press play on. Asked about directly: "there's a star heart fav button, I
 * don't know what it's for". It was for nothing, and that is the worst kind of control.
 *
 * ## One concept, not three
 *
 * The temptation here is a music tab: a library, artists, albums, playlists, favourites.
 * That is a second app inside the file manager and most of it would restate what the file
 * list already shows - the folder is the album and the folder name is the artist, in every
 * music collection that came off a phone.
 *
 * So there is exactly one new idea and everything else is expressed in it:
 *
 *  - **A playlist is a kept queue.** Make one from what is playing, or from a selection.
 *  - **Favourites is a playlist**, built in, with a fixed name and a heart instead of a row
 *    action. The heart adds and removes; there is no separate favourites store, no second
 *    list to keep in step, and no question about where a hearted song went.
 *  - **There is no library.** The file browser is the library. A playlist holds paths, and a
 *    path that has gone is shown as gone rather than hidden, because a playlist quietly
 *    shrinking is how somebody loses a list they spent an hour on.
 *
 * Pure, and tested, because every rule in here is one that silently does the wrong thing -
 * a de-duplication that drops the wrong copy, a move that renumbers off by one, a built-in
 * list that can be deleted by a button that should not have been on it.
 */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<VPath>,
    /** True for Favourites, which cannot be deleted or renamed. */
    val builtIn: Boolean = false,
    val madeAt: Long = 0L,
) {
    val size: Int get() = tracks.size

    fun contains(path: VPath): Boolean = path in tracks

    /**
     * Add to the end, unless it is already here.
     *
     * A playlist that accepts the same track twice is defensible for a party queue and wrong
     * for everything else, and the thing somebody actually does is press Add twice by
     * accident. Duplicates are refused rather than deduplicated afterwards, so the position
     * of the copy already in the list never moves under them.
     */
    fun plus(path: VPath): Playlist =
        if (contains(path)) this else copy(tracks = tracks + path)

    fun plusAll(paths: List<VPath>): Playlist =
        paths.fold(this) { acc, p -> acc.plus(p) }

    fun minus(path: VPath): Playlist = copy(tracks = tracks.filterNot { it == path })

    /** Reorder. Out-of-range indices are a no-op, because a drag can end anywhere. */
    fun move(from: Int, to: Int): Playlist {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return this
        val list = tracks.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(tracks = list)
    }
}

/**
 * Every playlist, with Favourites always first.
 *
 * Ordered by hand rather than by name or date: the list is short, it is read far more often
 * than it is reordered, and a list that re-sorts itself when something is renamed is a list
 * whose rows move while you are reaching for one.
 */
class Playlists(private val prefs: Prefs) {

    private val _items = MutableStateFlow(ensureFavourites(load()))
    val items: StateFlow<List<Playlist>> = _items.asStateFlow()

    val favourites: Playlist get() = _items.value.first { it.id == FAVOURITES }

    fun get(id: String): Playlist? = _items.value.firstOrNull { it.id == id }

    /** @return the new playlist's id. */
    fun create(name: String, tracks: List<VPath> = emptyList()): String {
        val id = "pl-" + System.currentTimeMillis().toString(36) + "-" + (_items.value.size)
        _items.value = _items.value + Playlist(
            id = id,
            name = cleanName(name, _items.value.map { it.name }),
            tracks = tracks.distinct(),
            madeAt = System.currentTimeMillis(),
        )
        save()
        return id
    }

    fun rename(id: String, name: String) = update(id) {
        if (it.builtIn) it else it.copy(name = cleanName(name, namesExcept(id)))
    }

    /** Deleting Favourites is refused here as well as hidden in the UI. */
    fun delete(id: String) {
        val p = get(id) ?: return
        if (p.builtIn) return
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    fun add(id: String, paths: List<VPath>) = update(id) { it.plusAll(paths) }

    fun remove(id: String, path: VPath) = update(id) { it.minus(path) }

    fun move(id: String, from: Int, to: Int) = update(id) { it.move(from, to) }

    fun clear(id: String) = update(id) { it.copy(tracks = emptyList()) }

    // ── favourites ──

    fun isFavourite(path: VPath): Boolean = favourites.contains(path)

    /** The heart. One call, because the button has one meaning in both states. */
    fun toggleFavourite(path: VPath): Boolean {
        val on = !isFavourite(path)
        update(FAVOURITES) { if (on) it.plus(path) else it.minus(path) }
        return on
    }

    /** Which playlists hold this track, so a dialogue can show the ticks already set. */
    fun holding(path: VPath): Set<String> =
        _items.value.filter { it.contains(path) }.map { it.id }.toSet()

    fun reload() { _items.value = ensureFavourites(load()) }

    private fun update(id: String, f: (Playlist) -> Playlist) {
        if (get(id) == null) return
        _items.value = _items.value.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun namesExcept(id: String) = _items.value.filterNot { it.id == id }.map { it.name }

    private fun load(): List<Playlist> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val paths = o.optJSONArray("tracks") ?: JSONArray()
                Playlist(
                    id = o.optString("id").ifEmpty { return@mapNotNull null },
                    name = o.optString("name", "Playlist"),
                    tracks = (0 until paths.length()).mapNotNull { j ->
                        runCatching { VPath.parse(paths.getString(j)) }.getOrNull()
                    },
                    builtIn = o.optBoolean("builtIn", false),
                    madeAt = o.optLong("madeAt", 0L),
                )
            }
        }.getOrElse { emptyList() }   // a corrupt blob loses playlists, never the app
    }

    private fun save() {
        val arr = JSONArray()
        for (p in _items.value) {
            val tracks = JSONArray()
            p.tracks.forEach { tracks.put(it.toString()) }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", tracks)
                    .put("builtIn", p.builtIn)
                    .put("madeAt", p.madeAt),
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    companion object {
        const val KEY = "playlists.v1"

        /** The built-in list the heart writes into. */
        const val FAVOURITES = "fav"
        const val FAVOURITES_NAME = "Favourites"

        /**
         * Favourites exists whether or not anything was ever saved, and exactly once.
         *
         * It is the heart's destination, and a heart whose list does not exist yet is a
         * control that works the second time it is pressed. It is also put FIRST however it
         * was stored, because it is the one list that is always there and a list that moves
         * depending on what else exists is a list you have to look for.
         */
        fun ensureFavourites(stored: List<Playlist>): List<Playlist> {
            val fav = stored.firstOrNull { it.id == FAVOURITES }
                ?: Playlist(FAVOURITES, FAVOURITES_NAME, emptyList(), builtIn = true)
            val rest = stored.filterNot { it.id == FAVOURITES }
            return listOf(fav.copy(name = FAVOURITES_NAME, builtIn = true)) + rest
        }

        /**
         * A name that is not blank and not already taken.
         *
         * Two playlists with the same name is not an error anybody will enjoy discovering
         * later - the dialogue that adds a track to one shows names, not ids.
         */
        fun cleanName(raw: String, taken: List<String>): String {
            val base = raw.trim().take(60).ifEmpty { "Playlist" }
            if (base !in taken) return base
            var n = 2
            while ("$base $n" in taken) n++
            return "$base $n"
        }
    }
}
