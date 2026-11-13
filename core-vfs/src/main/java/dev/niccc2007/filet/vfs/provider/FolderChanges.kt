package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells a folder on screen that something outside Filet changed it.
 *
 * Here rather than above the VFS because it watches the filesystem and asks the media store, which
 * PLAN.md R3 keeps in the provider layer. The caller passes an OS path - what `Vfs.osPath` answers -
 * and gets a callback, never a file.
 *
 * Bug identified here: a folder was re-read when Filet wrote to it and when Filet came back to
 * the front, and at no other time. A file deleted or added by anything else while Filet stayed
 * on screen - a computer over USB, the desktop tool, an app in a floating window, a download
 * finishing - stayed drawn, or stayed missing, until the list was pulled down by hand.
 *
 * Two sources, because neither sees everything:
 *
 * - **The folder itself** (`FileObserver`, inotify on the shared-storage mount). Sees every change
 *   made through the mount, by any app, and names nothing outside this folder.
 * - **The media index.** The media provider deletes and renames through the storage underneath
 *   the mount, which inotify above it never hears - that is how a gallery app's delete arrives.
 *   The index says only that something changed somewhere, so it is checked against the folder's
 *   own modified time first, and a change in another folder costs one `stat`, not a listing.
 *
 * Both are coalesced: the first change arms a re-read [SETTLE_MS] later and everything until then
 * rides on it, so a computer copying a thousand files is a re-read or two a second rather than a
 * thousand. The browser watches only while a pane is composed and the app is started.
 */
class FolderChanges(
    private val osPath: String,
    context: Context,
    private val onChange: () -> Unit,
) : AutoCloseable {

    private val main = Handler(Looper.getMainLooper())
    private val armed = AtomicBoolean(false)
    private val fromFolder = AtomicBoolean(false)
    @Volatile private var seen: Long = mtime()

    private fun mtime(): Long = runCatching { File(osPath).lastModified() }.getOrDefault(0L)

    private val fire = Runnable {
        armed.set(false)
        val now = mtime()
        if (shouldRelist(fromFolder.getAndSet(false), seen, now)) {
            seen = now
            onChange()
        }
    }

    private fun arm(folder: Boolean) {
        if (folder) fromFolder.set(true)
        if (armed.compareAndSet(false, true)) main.postDelayed(fire, SETTLE_MS)
    }

    @Suppress("DEPRECATION") // FileObserver(String, Int) is the only constructor below API 29.
    private val inotify = object : FileObserver(osPath, MASK) {
        override fun onEvent(event: Int, path: String?) {
            if (event and MASK != 0) arm(folder = true)
        }
    }

    private val index: AutoCloseable = MediaChanges.observe(context) { arm(folder = false) }

    init {
        inotify.startWatching()
    }

    override fun close() {
        runCatching { inotify.stopWatching() }
        runCatching { index.close() }
        main.removeCallbacks(fire)
    }

    companion object {
        /** What changes a listing: names coming and going, a write finishing, the folder itself going. */
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        const val SETTLE_MS = 600L

        /**
         * Whether a change is worth a re-read. Pure.
         *
         * Anything the folder's own watch heard is: it names this folder. A media-index change is
         * only when the folder's modified time moved, because the index fires for every folder on
         * the phone and a re-read of a five-thousand-photo folder because a screenshot landed
         * somewhere else is the cost this exists to avoid. A time that cannot be read (0) is
         * treated as moved: a folder that cannot be stat'ed is a folder that may have gone.
         */
        fun shouldRelist(fromFolder: Boolean, seenMtime: Long, nowMtime: Long): Boolean =
            fromFolder || nowMtime == 0L || nowMtime != seenMtime
    }
}
