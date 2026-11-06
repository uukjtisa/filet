package dev.niccc2007.filet.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.SystemClock
import dev.niccc2007.filet.vfs.VPath
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tell Android's media index about files Filet has just written, moved or removed.
 *
 * Bug identified here: a video received over Nearby was on the disk and absent from the
 * gallery. Nothing was wrong with the file. The gallery does not read the filesystem - it
 * reads MediaStore, and MediaStore only learns about a file when something announces it.
 * Android has not background-scanned storage for years, so a file written directly by an app
 * stays invisible to every gallery, music player and share sheet on the device until
 * `MediaScannerConnection.scanFile` is called for it.
 *
 * It matters just as much on the way out: scanning a path that no longer exists is how a
 * deleted file stops being a broken thumbnail in the gallery. The same call does both - the
 * scanner looks at the path and either indexes it or drops it.
 *
 * ## What is worth announcing
 *
 * Only real paths on the device's own storage. The rules below are a pure function because
 * getting them wrong is silent in both directions: announce too little and the gallery is
 * stale, announce a path inside a mounted archive and the scanner is handed something that is
 * not a file at all.
 *
 * ## Every way Filet makes the media provider work
 *
 * The media provider (`com.android.providers.media.module`, which some vendors list as "Media
 * picker" or "Media storage") is billed for more than MediaStore. On Android 11 and later the
 * shared-storage filesystem is a FUSE mount served BY the media provider, so every directory
 * listing, `stat` and file read any app makes under `/storage/emulated` runs in that process.
 * A file manager walking a tree is media-provider CPU whether or not it touches MediaStore.
 *
 * | Path | Kind of work | Trigger | How often | Bound |
 * |---|---|---|---|---|
 * | [announce]: file operations, extraction, received files, network-drive writes, "send to gallery" | scanner | Filet wrote, moved or removed a file | per write | [AnnounceBatcher]: one hand-over per 1.5 s of quiet, at most 8 s late, at most 1,000 paths |
 * | [announceFound], from [MediaCatchUp.sweep] | MediaStore query of every path, a walk, then the scanner | app opened, only while visible | at most every 6 h | 2,000 announces and 4,000 folders per pass; echoes of Filet's own writes dropped |
 * | `HotWatcher` callback in `FiletApp` | FUSE: the on-screen pane re-lists, plus a 4 s index pass only while visible | inotify event in a folder on screen, from any app; Filet's own writes skipped | once per folder per 1.5 s burst | watches stop when the app leaves the screen |
 * | New files (`HomeFeed`) | MediaStore query, 2,000 rows by `DATE_ADDED` | tab open/resume, refresh, after Filet's own writes are scanned, and a MediaStore observer only while the tab is on screen | per open; changes coalesced over 2 s | 10,000 rows held, then range queries for the date jump |
 * | `MediaChangeJobService` in `IndexCoordinator` | RETIRED - cancels itself where it was armed | - | never | - |
 * | `IndexWorker` | FUSE: a 2 min index pass over every root | WorkManager | every 12 h, on charge only | 2 min |
 * | index pass at launch | FUSE: a 25 s pass, or a full build | app opened | past the 30 min retry and 6 h stale gates | its budget |
 * | `MediaCategory` (0.1.20 only) | MediaStore query | a Home category opened | per open | row `LIMIT` |
 * | [Thumbnails] | FUSE: reads of the file previewed | a row on screen | per visible row | memory cache; on screen only |
 *
 * ## What this table used to say, and why it changed
 *
 * Two rows had no bound, and they are the shape a battery complaint takes. The watcher started
 * a 4 s index pass on every inotify event, undebounced, for as long as the process lived -
 * which is days, behind a playback or sharing service. And the index armed a job on
 * `MediaStore.Files` with `NOTIFY_FOR_DESCENDANTS`, so ANY media change on the phone - another
 * app's download, a messenger receiving a photo - woke a 20 s crawl of every root, in the
 * background, which re-armed itself, with the index on by default.
 *
 * Neither was a leak or a loop: nothing Filet does in response writes to shared storage, so
 * neither fed itself. Each was a standing cost proportional to everything ELSE the phone does,
 * billed to a system process - which is why it showed as the media provider's battery and not
 * Filet's, and why it can account for a majority share where nothing in the foreground could.
 */
object MediaAnnounce {

    /**
     * Schemes that correspond to a real path on this device.
     *
     * A path inside a mounted container (`zip:`, `apk:`) is an entry in a file, not a file, and
     * a path on a remote (`sftp:`, `ftp:`, `peer:`) is not on this device at all. Neither can
     * be given to the scanner.
     */
    private const val LOCAL = "local"

    /**
     * Directories whose contents are deliberately not part of the user's media.
     *
     * An app's own private storage is excluded by the platform anyway, but Filet's cache and
     * thumbnail directories are on shared storage and would otherwise put every generated
     * thumbnail into the gallery.
     */
    private val PRIVATE_MARKERS = listOf("/Android/data/", "/Android/obb/", "/.thumbnails/")

    /** Whether [path] names something the media index should be told about. */
    fun worthAnnouncing(path: VPath): Boolean {
        if (path.scheme != LOCAL) return false
        // Always absolute: VPath.normalise guarantees it, so there is no relative case to
        // guard against here.
        val s = path.path
        // A container mount is addressed with `!`, so its presence means this is an entry
        // inside a file rather than a file.
        if (s.contains("!")) return false
        if (PRIVATE_MARKERS.any { s.contains(it) }) return false
        return true
    }

    /** The subset of [paths] worth handing to the scanner, as plain filesystem strings. */
    fun filesystemPaths(paths: Collection<VPath>): List<String> =
        paths.filter(::worthAnnouncing).map { it.path }.distinct()

    /**
     * Announce [paths], which Filet has just written, moved or removed.
     *
     * Safe to call with anything: paths that are not real files on this device are dropped
     * first, and an empty list does nothing. Queued and handed over in batches - see
     * [AnnounceBatcher] - rather than one scanner session per call. Never gated on the app being
     * visible: telling the index about a write is part of the write, and a copy that finishes
     * behind a foreground service must still reach the gallery.
     */
    fun announce(context: Context, paths: Collection<VPath>) {
        val real = filesystemPaths(paths)
        if (real.isEmpty()) return
        // Which of them are now missing is asked straight away and off the caller's thread,
        // not when the batch is handed over: the batch can be 8 s late, and that wait is the
        // lag [justRemoved] exists to hide.
        clock.execute { runCatching { justRemoved.noted(real) } }
        synchronized(lock) {
            appContext = context.applicationContext
            batcher.written(real, SystemClock.elapsedRealtime())
            schedule()
        }
    }

    /**
     * Announce [paths] that were FOUND rather than written - the catch-up sweep's. Any that
     * Filet wrote within the echo window are dropped: the scanner already has them in hand.
     */
    fun announceFound(context: Context, paths: Collection<VPath>) {
        val real = filesystemPaths(paths)
        if (real.isEmpty()) return
        synchronized(lock) {
            appContext = context.applicationContext
            batcher.found(real, SystemClock.elapsedRealtime())
            schedule()
        }
    }

    /**
     * Whether Filet itself just wrote [path]. For anything that reacts to a change on disk, so
     * that hearing Filet's own write does not set off work about it.
     */
    fun wroteRecently(path: VPath): Boolean =
        synchronized(lock) { batcher.wroteRecently(path.path, SystemClock.elapsedRealtime()) }

    /**
     * Whether a person can see Filet right now: an activity in front, or visible behind a
     * dialogue.
     *
     * A foreground SERVICE alone is not this. Filet's process routinely lives for days behind
     * one - playback, sharing - and observing or querying the media index for a screen nobody
     * is looking at is work the battery pays for in two processes. Read from the process
     * importance rather than from activity callbacks, so it is right on the first call with
     * nothing registered first.
     */
    fun visible(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        Visibility.isVisibleImportance(info.importance)
    }.getOrDefault(false)

    // ── the queue ──

    private val lock = Any()
    private val batcher = AnnounceBatcher()
    private var appContext: Context? = null
    private var timer: ScheduledFuture<*>? = null

    /** One daemon thread. The scanner call is itself asynchronous; this only times it. */
    private val clock by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "media-announce").apply { isDaemon = true }
        }
    }

    /**
     * Called once a batch has been indexed - every path in it scanned. For readers of the media
     * index that want Filet's own writes, which are only there AFTER this.
     */
    @Volatile
    var onScanned: (() -> Unit)? = null

    /** What Filet just removed and the index may still list. See [JustRemoved]. */
    val justRemoved = JustRemoved()

    private val _indexed = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Counts finished batches. A category keys its read on it, so a file Filet writes shows up
     * there once the index has it, the way New files already did through [onScanned]. A
     * category re-read only on its refresh button before this, so a picture copied back to
     * where one had been deleted stayed missing from an open Photos.
     */
    val indexed: kotlinx.coroutines.flow.StateFlow<Int> = _indexed

    /** Must hold [lock]. Points the one pending timer at the batch's due time. */
    private fun schedule() {
        val due = batcher.dueAt()
        timer?.cancel(false)
        timer = null
        if (due < 0) return
        val wait = (due - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        timer = clock.schedule(::flush, wait, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        val (batch, context) = synchronized(lock) {
            batcher.take(SystemClock.elapsedRealtime()) to appContext
        }
        if (batch.isNotEmpty() && context != null) {
            // Guarded: a media index that cannot be reached must never fail anything.
            runCatching {
                val left = java.util.concurrent.atomic.AtomicInteger(batch.size)
                MediaScannerConnection.scanFile(
                    context,
                    batch.toTypedArray(),
                    // Null lets the scanner work the type out from the file, which is right:
                    // Filet moves files whose extension may be wrong or absent, and asserting a
                    // type it cannot back up would put a video in the gallery as something else.
                    null,
                ) { _, _ ->
                    // One callback per path; the batch is in the index when the last arrives.
                    if (left.decrementAndGet() == 0) {
                        runCatching { justRemoved.recheck() }
                        _indexed.value = _indexed.value + 1
                        runCatching { onScanned?.invoke() }
                    }
                }
            }
        }
        synchronized(lock) { schedule() }
    }
}
