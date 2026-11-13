package dev.niccc2007.filet.icons

import android.content.Context
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.index.FileIndex
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The icons chosen for folders with Change icon.
 *
 * ## Where they are kept, and how they follow a folder
 *
 * In Filet, not in the folder: writing a `desktop.ini`-style file into somebody's folder to
 * remember a colour would be Filet leaving litter in their storage. Each choice is kept with the
 * folder's path and its file id from the index:
 *
 *  - **Renamed or moved in Filet:** the file system reports it and the path is updated at once.
 *  - **Renamed or moved outside Filet:** the index still knows the folder by its id, and the
 *    path is looked up again when Filet starts.
 *  - **Carried to another phone in a settings export:** ids mean nothing there, so a choice is
 *    matched by path only. [KEY_PHONE] marks which install made each id.
 *
 * Lookups while drawing are by path, from memory: a list of folders asks once per row per frame.
 */
class FolderStyles internal constructor(
    private val prefs: Prefs,
    private val index: FileIndex,
    vfs: Vfs,
) {
    private data class Entry(val path: String, val id: Long?, val phone: String?, val look: FolderLook)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val phone: String = prefs.getString(KEY_PHONE) ?: java.util.UUID.randomUUID().toString().also { prefs.putString(KEY_PHONE, it) }

    private val entries = MutableStateFlow(load())

    /** Every chosen look, by the folder's path. */
    private val _byPath = MutableStateFlow(entries.value.associate { it.path to it.look })
    val byPath: StateFlow<Map<String, FolderLook>> = _byPath.asStateFlow()

    init {
        vfs.onRelocated { from, to, isDir -> if (isDir) relocated(from, to) }
        scope.launch { refind() }
    }

    /** The look chosen for [path], or null when none was. */
    fun lookOf(path: VPath): FolderLook? = _byPath.value[path.toString()]

    /** Dress every one of [paths] as [look]. */
    fun set(paths: List<VPath>, look: FolderLook) {
        val keys = paths.map { it.toString() }.toSet()
        update(entries.value.filterNot { it.path in keys } + paths.map { Entry(it.toString(), null, phone, look) })
        // The ids are found after, off the main thread; until then the path is enough.
        scope.launch {
            val ids = paths.associate { it.toString() to runCatching { index.idFor(it) }.getOrNull() }
            update(entries.value.map { e -> if (e.path in keys && e.id == null) e.copy(id = ids[e.path]) else e })
        }
    }

    /** Back to the default look: the standard folder's, or plain. */
    fun reset(paths: List<VPath>) {
        val keys = paths.map { it.toString() }.toSet()
        update(entries.value.filterNot { it.path in keys })
    }

    private fun relocated(from: VPath, to: VPath) {
        val moved = entries.value.map { e -> movedTo(e.path, from.toString(), to.toString())?.let { e.copy(path = it) } ?: e }
        if (moved != entries.value) update(moved)
    }

    /** Look each folder up again by its id: one moved outside Filet is found where it went. */
    private suspend fun refind() {
        val now = entries.value.map { e ->
            if (e.id == null || e.phone != phone) return@map e
            val at = runCatching { index.pathFor(e.id) }.getOrNull() ?: return@map e
            if (at.toString() == e.path) e else e.copy(path = at.toString())
        }
        if (now != entries.value) update(now)
    }

    private fun update(next: List<Entry>) {
        entries.value = next
        _byPath.value = next.associate { it.path to it.look }
        save(next)
    }

    private fun load(): List<Entry> = runCatching {
        val arr = JSONArray(prefs.getString(KEY) ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val path = o.optString("path").ifEmpty { return@mapNotNull null }
            Entry(
                path = path,
                id = if (o.has("id")) o.optLong("id") else null,
                phone = o.optString("phone").ifEmpty { null },
                look = FolderLook(
                    emblem = o.optString("emblem").ifEmpty { null },
                    colour = o.optString("colour").ifEmpty { "steel" },
                    cover = o.optString("cover").ifEmpty { null },
                ),
            )
        }
    }.getOrElse { emptyList() }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().put("path", e.path).put("colour", e.look.colour).apply {
                    e.id?.let { put("id", it) }
                    e.phone?.let { put("phone", it) }
                    e.look.emblem?.let { put("emblem", it) }
                    e.look.cover?.let { put("cover", it) }
                }
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    companion object {
        /** The chosen looks: a JSON list of path, id, the phone that made the id, emblem, colour, cover. */
        const val KEY = "folder.styles.v1"

        /** This install's own mark, so an id is only trusted on the phone that made it. */
        const val KEY_PHONE = "folder.styles.phone"

        @Volatile private var one: FolderStyles? = null

        fun of(context: Context): FolderStyles = one ?: synchronized(this) {
            one ?: FiletApp.graphOf(context).let { g -> FolderStyles(g.prefs, g.index, g.vfs) }.also { one = it }
        }
    }
}

/**
 * Where [path] is after the folder [from] moved to [to]: the folder itself, or anything inside it,
 * carried along. Null when [path] was not in what moved. A folder whose name only starts the
 * same (Music beside Music2) is not inside it.
 */
internal fun movedTo(path: String, from: String, to: String): String? = when {
    path == from -> to
    path.startsWith("$from/") -> to + path.removePrefix(from)
    else -> null
}

/**
 * The look a folder is drawn with: the one chosen for it, else the standard folder's, else plain.
 */
fun lookFor(styles: Map<String, FolderLook>, path: VPath): FolderLook =
    styles[path.toString()] ?: DefaultFolders.lookOf(path) ?: FolderLook.PLAIN
