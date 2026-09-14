package dev.niccc2007.filet.index

import android.os.Build
import android.os.FileObserver
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.io.File

/**
 * Tier 1 of the freshness stack: inotify on hot directories (SEARCH.md §4.1).
 *
 * **You cannot watch a filesystem.** `FileObserver` costs one inotify watch per directory
 * against a per-process ceiling that is commonly around 8192, and a mid-sized phone has more
 * directories than that. Anyone who has tried to watch everything has shipped an app that
 * silently stops noticing changes on a full device.
 *
 * So this watches *shallow and hot*: the tracked folders, plus whatever the open panes are
 * looking at. Everything else is caught by directory-mtime validation on read and by the
 * generation sweep, neither of which needs a watch at all.
 */
class HotWatcher(
    private val vfs: Vfs,
    /**
     * @param dir the watched directory.
     * @param name the entry inside it that changed, when the kernel said. Passing this on
     *   rather than discarding it is what lets a listener react to ONE file instead of
     *   re-reading the directory - which matters enormously for a folder holding 1700
     *   screenshots, where the listing is seconds and the stat is microseconds.
     */
    private val onChanged: (dir: VPath, name: String?) -> Unit,
) {
    private val watches = HashMap<String, FileObserver>()

    /** Hard ceiling well under the kernel's, so Filet never exhausts the process's watches. */
    private val cap = 96

    @Synchronized
    fun watch(paths: Collection<VPath>) {
        val wanted = LinkedHashMap<String, VPath>()
        for (p in paths.take(cap)) {
            val os = runCatching { vfs.osPath(p) }.getOrNull() ?: continue
            wanted[os] = p
        }
        // Drop watches nobody asked for any more before adding new ones, so the cap is a
        // ceiling on live watches rather than on calls to this method.
        for (gone in watches.keys - wanted.keys) {
            watches.remove(gone)?.stopWatching()
        }
        for ((os, vpath) in wanted) {
            if (watches.containsKey(os)) continue
            val obs = observerFor(os, vpath) ?: continue
            watches[os] = obs
            runCatching { obs.startWatching() }
        }
    }

    @Synchronized
    fun stopAll() {
        watches.values.forEach { runCatching { it.stopWatching() } }
        watches.clear()
    }

    @Synchronized
    fun count(): Int = watches.size

    private fun observerFor(os: String, vpath: VPath): FileObserver? {
        val f = File(os)
        if (!f.isDirectory) return null
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(f, mask) {
                override fun onEvent(event: Int, path: String?) = onChanged(vpath, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(os, mask) {
                override fun onEvent(event: Int, path: String?) = onChanged(vpath, path)
            }
        }
    }
}
