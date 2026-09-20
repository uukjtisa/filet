package dev.niccc2007.filet.media

import android.content.Context
import android.media.MediaScannerConnection
import dev.niccc2007.filet.vfs.VPath

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
     * Announce [paths] to the media index.
     *
     * Safe to call with anything: paths that are not real files on this device are dropped
     * first, and an empty list does nothing. Guarded, because a media index that cannot be
     * reached must never fail the file operation that succeeded.
     */
    fun announce(context: Context, paths: Collection<VPath>) {
        val real = filesystemPaths(paths)
        if (real.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                real.toTypedArray(),
                // Null lets the scanner work the type out from the file, which is right: Filet
                // moves files whose extension may be wrong or absent, and asserting a type it
                // cannot back up would put a video in the gallery as something else.
                null,
                null,
            )
        }
    }
}
