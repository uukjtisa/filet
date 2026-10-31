package dev.niccc2007.filet.data

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * `filet.json`, `bookmarks.json` and `playlists.json`, kept in step with the store both ways.
 * The format is [ConfigCodec]'s; this is when and where.
 *
 * ## Where
 *
 * The root of the Filet folder, beside Logs and Scripts - [FiletStore.root], which is shared
 * storage once Filet can reach it and the app's own external folder until then. Not under the
 * cache, and not moved by the App data location setting: these are what Filet must not lose,
 * the same rule [FiletStore.Area.SETTINGS] follows.
 *
 * ## When
 *
 * - **Filet changes a setting:** the file is written half a second later, so a slider dragged
 *   across its range is one write and not sixty.
 * - **The file is saved while Filet is open:** a [FileObserver] on the folder hears it and the
 *   change applies where it stands - [Prefs.reload] re-reads every flow, so nothing needs a
 *   restart.
 * - **The file was edited while Filet was away, or the observer missed it:** coming to the
 *   foreground compares the file against what Filet last wrote or read, and applies it if it
 *   moved.
 *
 * ## How it tells its own writes from somebody else's
 *
 * By content, not by time. For each file it remembers the hash of the text it last wrote or
 * applied ("seen") and the hash of what the store rendered to at that moment ("rendered").
 *
 * - The file's text is not "seen": somebody else wrote it. Apply it, and leave their text as it
 *   is - rewriting it in Filet's own layout the moment it was saved would fight an editor that
 *   still has it open.
 * - The text is "seen" but the store now renders differently: Filet changed something. Write.
 * - Both match: nothing to do. This is what an apply followed by the store's own change
 *   notifications comes to, which is why an edit is not immediately rewritten.
 *
 * The write is a temporary file renamed over the old one, so an editor or a sync app never
 * reads half a file, and "seen" is recorded before the rename so the observer's echo of Filet's
 * own write finds it already known.
 */
class ConfigSync(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val bookmarks: Bookmarks,
    private val playlists: dev.niccc2007.filet.playback.Playlists,
    /** The other stores that cache what a settings file can change. */
    private val afterApply: () -> Unit,
    /** A line for the person editing: what was applied, or what was skipped and where. */
    private val report: (String) -> Unit,
) {
    /** Hashes of what was last seen and rendered, per file. Its own store, so writing it is not a settings change. */
    private val state = context.getSharedPreferences("filet.configsync", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var watcher: FileObserver? = null
    private var listening: AutoCloseable? = null
    private val pending = HashMap<String, Job>()

    private abstract inner class Synced(val name: String) {
        /** True for a store key whose change this file carries. */
        abstract fun carries(key: String): Boolean
        abstract fun render(existing: String?): String
        /** Store what the file says. The problems are what was not applied. */
        abstract fun apply(text: String): List<ConfigCodec.Problem>
        fun file(): File = File(FiletStore.root(context), name)
    }

    private val settings = object : Synced(SETTINGS_FILE) {
        private val keys = ConfigKeys.FILE.map { it.key }.toSet()
        override fun carries(key: String) = key in keys
        override fun render(existing: String?): String {
            val unknown = existing
                ?.let { ConfigCodec.parse(it, ConfigKeys.FILE) as? ConfigCodec.Parsed.Ok }
                ?.unknown.orEmpty()
            return ConfigCodec.render(prefs.snapshot(keys), ConfigKeys.FILE, unknown)
        }
        override fun apply(text: String): List<ConfigCodec.Problem> =
            when (val p = ConfigCodec.parse(text, ConfigKeys.FILE)) {
                is ConfigCodec.Parsed.Refused -> listOf(p.problem)
                is ConfigCodec.Parsed.Ok -> {
                    prefs.restore(p.values, p.reset)
                    prefs.reload()
                    afterApply()
                    p.problems
                }
            }
    }

    private inner class ListFile(name: String, private val key: String, private val reload: () -> Unit) : Synced(name) {
        override fun carries(key: String) = key == this.key
        override fun render(existing: String?) = ConfigCodec.renderList(prefs.getString(key))
        override fun apply(text: String): List<ConfigCodec.Problem> {
            val (stored, problem) = ConfigCodec.parseList(text)
            if (stored != null) {
                prefs.putString(key, stored)
                reload()
            }
            return listOfNotNull(problem)
        }
    }

    private val files: List<Synced> = listOf(
        settings,
        ListFile(BOOKMARKS_FILE, "bookmarks") { bookmarks.reload() },
        ListFile(PLAYLISTS_FILE, PLAYLISTS_KEY) { playlists.reload() },
    )

    /** Start hearing the store, and bring every file up to date. Once, at startup. */
    fun start() {
        if (listening != null) return
        listening = prefs.onChange { key ->
            for (f in files) if (key == null || f.carries(key)) schedule(f, WRITE_SETTLE_MS)
        }
        scope.launch { syncAll() }
    }

    /** Filet came to the foreground: watch the folder, and catch up on anything edited meanwhile. */
    fun onForeground() {
        if (watcher == null) watcher = watch()
        scope.launch { syncAll() }
    }

    /** Filet left the foreground: stop watching. A change made now is caught on the way back. */
    fun onBackground() {
        watcher?.stopWatching()
        watcher = null
    }

    /** Where the settings file is, for "Edit as file". */
    fun settingsPath(): String = settings.file().absolutePath

    /** Write the settings file now if it is missing, so "Edit as file" has something to open. */
    suspend fun ensureSettingsFile() = sync(settings)

    private fun schedule(f: Synced, settle: Long) {
        synchronized(pending) {
            pending[f.name]?.cancel()
            pending[f.name] = scope.launch {
                delay(settle)
                sync(f)
            }
        }
    }

    private suspend fun syncAll() {
        for (f in files) sync(f)
    }

    private suspend fun sync(f: Synced) = lock.withLock {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { syncLocked(f) }
        }
    }

    private fun syncLocked(f: Synced) {
        val file = f.file()
        val text = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        val seen = state.getString("seen." + f.name, null)
        if (text != null && hash(text) != seen) {
            val problems = f.apply(text)
            remember(f, text, f.render(text))
            report(
                if (problems.isEmpty()) "${f.name} applied"
                else problems.joinToString("\n") { it.describe(f.name) },
            )
            return
        }
        val out = f.render(text)
        if (text != null && hash(out) == state.getString("rendered." + f.name, null)) return
        if (out == text) {
            remember(f, out, out)
            return
        }
        write(file, out)
        remember(f, out, out)
    }

    private fun remember(f: Synced, text: String, rendered: String) {
        state.edit()
            .putString("seen." + f.name, hash(text))
            .putString("rendered." + f.name, hash(rendered))
            .commit()
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        // "seen" is recorded by the caller after this returns, and the observer's echo of the
        // rename waits on the same lock, so it always finds the new text already known.
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    @Suppress("DEPRECATION") // The File constructor is API 29; the path one works everywhere.
    private fun watch(): FileObserver? = runCatching {
        val dir = FiletStore.root(context)
        val names = files.associateBy { it.name }
        object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                val f = names[path ?: return] ?: return
                schedule(f, READ_SETTLE_MS)
            }
        }.also { it.startWatching() }
    }.getOrNull()

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val SETTINGS_FILE = "filet.json"
        const val BOOKMARKS_FILE = "bookmarks.json"
        const val PLAYLISTS_FILE = "playlists.json"
        const val PLAYLISTS_KEY = "playlists.v1"

        /** After a setting changes. Long enough that a slider drag is one write. */
        const val WRITE_SETTLE_MS = 500L

        /** After the file changes. An editor's save can be several writes in a row. */
        const val READ_SETTLE_MS = 300L
    }
}
