package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * One entry in the shared set.
 *
 * @param linked true when this is a **proxy**: a pointer at a file that lives somewhere else
 *   on the device rather than a copy inside the shared folder. A 4 GB video is shared by
 *   pointing at it, which costs zero bytes and starts instantly.
 */
data class SharedEntry(
    val token: String,
    val path: VPath,
    val alias: String,
    val addedAt: Long,
    val linked: Boolean,
    val expires: Long = 0,
)

/**
 * What this device is offering.
 *
 * > A Shared set is the union of a **real folder** and a set of **proxy entries**, rendered
 * > as one listing (NEARBY.md §1.1).
 *
 * Two rules carry the security of the whole feature:
 *
 * 1. **Serve by token, never by path.** The HTTP surface is `/f/<token>`. A path never enters
 *    a URL, so path traversal is not mitigated - it is *unrepresentable*.
 * 2. **Nothing is exposed by default.** Sharing is a mode you enter. The server does not run
 *    until it is turned on, and the set is empty until something is put in it.
 *
 * Proxies store the path today; PLAN.md L1's stable IDs make that a node id once the index is
 * the source of truth for identity, at which point a moved file keeps its share automatically.
 */
class SharedSet(
    context: Context,
    private val vfs: Vfs,
    private val prefs: Prefs,
    /**
     * The real folder half of the set.
     *
     * On the shared volume rather than app-specific storage: the point is that other apps and
     * the user can drop things into it, and app-specific storage is invisible to both. A
     * parameter rather than a constant so a test can point it at a fixture - the alternative
     * is a server test that writes into the user's actual shared folder.
     */
    val folder: VPath = VPath.of("local", "/storage/emulated/0/Filet/Shared"),
    /** Where an accepted incoming file lands. Never the shared set itself. */
    val quarantine: VPath = VPath.of("local", "/storage/emulated/0/Filet/Received"),
) {

    private val random = SecureRandom()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<SharedEntry>> = _entries.asStateFlow()

    suspend fun ensureFolders() {
        for (p in listOf(folder, quarantine)) {
            runCatching { if (vfs.stat(p) == null) vfs.create(p, isDir = true) }
        }
    }

    /** Add a proxy entry: shared in place, never copied. */
    fun share(node: VNode, alias: String = node.name): SharedEntry {
        _entries.value.firstOrNull { it.path == node.path }?.let { return it }
        val entry = SharedEntry(newToken(), node.path, alias, System.currentTimeMillis(), linked = true)
        _entries.value = _entries.value + entry
        save()
        return entry
    }

    fun revoke(token: String) {
        _entries.value = _entries.value.filterNot { it.token == token }
        save()
    }

    fun revokeAll() {
        _entries.value = emptyList()
        save()
    }

    fun isShared(path: VPath) = _entries.value.any { it.path == path }

    fun byToken(token: String): SharedEntry? = _entries.value.firstOrNull { it.token == token }

    /**
     * Everything a peer or browser may see: the real folder's contents plus the proxies.
     *
     * The real folder is read live rather than cached, so dropping a file into it with the
     * other pane makes it appear without any "refresh shares" step.
     */
    suspend fun listing(): List<SharedView> {
        val out = ArrayList<SharedView>()
        val real = runCatching { vfs.list(folder) }.getOrElse { emptyList() }
        for (node in real) {
            if (node.hidden) continue
            out += SharedView(tokenForRealFile(node.path), node, linked = false)
        }
        for (e in _entries.value) {
            if (e.expires in 1..System.currentTimeMillis()) continue
            val node = runCatching { vfs.stat(e.path) }.getOrNull()
            // An unavailable source fails cleanly: the row simply is not offered, and a fetch
            // of its token answers 410 Gone rather than hanging.
            if (node == null) continue
            out += SharedView(e.token, node.copy(), linked = true, alias = e.alias)
        }
        return out.sortedWith(compareByDescending<SharedView> { it.node.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * List INSIDE a shared folder.
     *
     * The browser needs to walk a shared directory the way the app does - a shared folder is
     * a folder, not an opaque bundle. Tokens are minted for the children as they are listed,
     * which is what keeps the serve-by-token rule intact: a subfolder is reachable only
     * because something already reachable listed it, and a path still never appears in a URL.
     *
     * @param token a directory token previously handed out.
     * @return null when the token is not a directory this set has offered.
     */
    suspend fun listingIn(token: String): List<SharedView>? {
        val dir = resolve(token) ?: return null
        val node = runCatching { vfs.stat(dir) }.getOrNull() ?: return null
        if (!node.isDir) return null
        if (!isReachable(dir)) return null

        val kids = runCatching { vfs.list(dir) }.getOrElse { return emptyList() }
        return kids
            .filterNot { it.hidden }
            .map { SharedView(tokenForRealFile(it.path), it, linked = false) }
            .sortedWith(compareByDescending<SharedView> { it.node.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * Is this path inside something that was actually shared?
     *
     * Tokens are minted only for things that were listed, so a token IS the permission - but
     * a token outliving a revoke would otherwise keep a subtree reachable. Checking the
     * ancestry on every walk means un-sharing a folder closes everything under it at once.
     */
    fun isReachable(path: VPath): Boolean {
        if (path == folder || folder.contains(path)) return true
        if (path == quarantine) return false
        return _entries.value.any { it.path == path || it.path.contains(path) }
    }

    /** Where a directory token sits relative to the share root, for the browser's breadcrumb. */
    fun trailTo(token: String): List<Pair<String, String>> {
        val target = resolve(token) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var cur: VPath? = target
        while (cur != null && cur != folder && !_entries.value.any { it.path == cur }) {
            out += tokenForRealFile(cur) to cur.name
            cur = cur.parent
        }
        cur?.takeIf { it != folder }?.let { out += tokenForRealFile(it) to it.name }
        return out.reversed()
    }

    /**
     * Tokens for files that live in the real folder.
     *
     * Derived and remembered per session rather than persisted: a file that was in the folder
     * last week and is there again gets a fresh token, which is the safer default for a URL
     * that may have been pasted somewhere.
     */
    @Synchronized
    private fun tokenForRealFile(path: VPath): String =
        realTokens.getOrPut(path.toString()) { newToken() }

    private val realTokens = HashMap<String, String>()

    @Synchronized
    fun resolve(token: String): VPath? {
        byToken(token)?.let { return it.path }
        realTokens.entries.firstOrNull { it.value == token }?.let {
            return runCatching { VPath.parse(it.key) }.getOrNull()
        }
        return null
    }

    /** Invalidate every URL handed out so far. Called when sharing stops. */
    @Synchronized
    fun endSession() = realTokens.clear()

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun load(): List<SharedEntry> {
        val raw = prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val p = runCatching { VPath.parse(o.getString("path")) }.getOrNull() ?: return@mapNotNull null
                SharedEntry(
                    token = o.getString("token"),
                    path = p,
                    alias = o.optString("alias", p.name),
                    addedAt = o.optLong("at"),
                    linked = o.optBoolean("linked", true),
                    expires = o.optLong("expires"),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save() {
        val arr = JSONArray()
        for (e in _entries.value) {
            arr.put(
                JSONObject()
                    .put("token", e.token).put("path", e.path.toString())
                    .put("alias", e.alias).put("at", e.addedAt)
                    .put("linked", e.linked).put("expires", e.expires)
            )
        }
        prefs.putString(KEY, arr.toString())
    }

    private companion object { const val KEY = "nearby.shares" }
}

/** A row as the server and the peer see it. */
data class SharedView(
    val token: String,
    val node: VNode,
    val linked: Boolean,
    val alias: String? = null,
) {
    val name: String get() = alias ?: node.name
    val size: Long get() = node.size
    val isDir: Boolean get() = node.isDir
}
