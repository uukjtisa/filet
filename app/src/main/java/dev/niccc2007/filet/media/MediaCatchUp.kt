package dev.niccc2007.filet.media

import android.content.Context
import android.provider.MediaStore
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs

/**
 * Register media that is on the device but missing from the gallery.
 *
 * Bug identified: announcing a file as Filet writes it only helps files written after the fix.
 * Everything already on the disk stays invisible to the gallery, which is what was reported -
 * videos listed perfectly by every file manager and absent from the gallery.
 *
 * So the rule is the one asked for: **if Filet tracks a file, Filet registers it.** Stated as a
 * reconcile rather than a hook, because a hook can only ever catch what happens next.
 *
 * ## Why it is a set difference and not a scan
 *
 * MediaStore can be asked, in one query, for every path it already holds. Everything Filet can
 * see that is not in that answer is the work. Handing the scanner the whole device instead
 * would re-read hundreds of thousands of files to discover that it already knew about nearly
 * all of them.
 *
 * The comparison itself is a pure function, because both ways of getting it wrong are silent:
 * miss a file and the gallery stays wrong with no error, re-announce everything and the device
 * grinds with nothing to show for it.
 */
object MediaCatchUp {

    /**
     * Extensions a gallery or music player would expect to show.
     *
     * Deliberately not every type Filet can open. A document is not missing from the gallery
     * when the gallery was never going to show it, and announcing one is work for nothing.
     */
    private val MEDIA = setOf(
        // pictures
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "dng", "tiff",
        // video
        "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mpg", "mpeg",
        // audio
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid",
    )

    /**
     * Whether a gallery or music player would be expected to list this.
     *
     * A leading dot marks a hidden file, it does not separate an extension: `.mp4` is a hidden
     * file named "mp4" with no extension at all, the same shape as `.nomedia`. Reading it as an
     * extension would announce hidden files to the gallery, which is the opposite of what a
     * leading dot means.
     */
    fun isMedia(name: String): Boolean {
        val dot = name.indexOf('.', startIndex = 1)
        if (dot < 0) return false
        return name.substringAfterLast('.').lowercase() in MEDIA
    }

    /**
     * What the media index has not been told about.
     *
     * @param onDisk every media path Filet can see.
     * @param known every path MediaStore already holds.
     *
     * Order is preserved so a sweep announces in the order it walked, which keeps a partial
     * run predictable rather than arbitrary.
     */
    fun missing(onDisk: Collection<String>, known: Set<String>): List<String> =
        onDisk.asSequence().filterNot { it in known }.distinct().toList()

    /**
     * Every path MediaStore currently holds.
     *
     * One query over the whole external volume. `DATA` is deprecated for *writing* and is still
     * the only column that answers "which file on disk is this row", which is the question here.
     */
    fun knownToMediaStore(context: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            val uri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Files.FileColumns.DATA),
                null,
                null,
                null,
            )?.use { c ->
                val col = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                if (col >= 0) while (c.moveToNext()) c.getString(col)?.let(out::add)
            }
        }
        return out
    }

    /**
     * Walk [roots] and register whatever the gallery is missing.
     *
     * @param limit a ceiling on one pass. A device that has never been scanned could otherwise
     *   hand the scanner tens of thousands of files at once; the remainder is picked up by the
     *   next sweep, because the ones already done stop being missing.
     * @return how many were announced.
     */
    suspend fun sweep(
        context: Context,
        vfs: Vfs,
        roots: List<VPath>,
        limit: Int = 2_000,
    ): Int {
        val known = knownToMediaStore(context)
        val found = ArrayList<VPath>()
        for (root in roots) {
            if (found.size >= limit) break
            walk(vfs, root, found, limit, known)
        }
        if (found.isEmpty()) return 0
        MediaAnnounce.announce(context, found)
        return found.size
    }

    /**
     * Depth-first, stopping at [limit].
     *
     * Guarded per directory: one unreadable folder must not end the sweep, because the folder
     * most likely to refuse is somebody else's app data and the interesting ones are usually
     * further along.
     */
    private suspend fun walk(
        vfs: Vfs,
        dir: VPath,
        into: MutableList<VPath>,
        limit: Int,
        known: Set<String>,
    ) {
        if (into.size >= limit) return
        val children = runCatching { vfs.list(dir) }.getOrNull() ?: return
        // A folder holding .nomedia is deliberately hidden from galleries, and that applies to
        // THIS folder and everything under it - announcing any of it would override a choice
        // somebody made on purpose.
        if (children.any { it.name == ".nomedia" }) return
        for (child in children) {
            if (into.size >= limit) return
            if (child.isDir) {
                walk(vfs, child.path, into, limit, known)
            } else if (isMedia(child.name) &&
                child.path.path !in known &&
                MediaAnnounce.worthAnnouncing(child.path)
            ) {
                into += child.path
            }
        }
    }
}
