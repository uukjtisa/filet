package dev.niccc2007.filet.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File

/**
 * Previews the phone has already made, read from its media store instead of decoded again.
 *
 * Bug identified here: a folder Filet had never shown came up as a wall of type icons that
 * filled in one by one, while the phone's gallery showed the same folder at once. The gallery
 * was not decoding faster. It was not decoding at all: the system makes a preview of every photo
 * and video when it scans them, keeps it on disk, and the gallery reads that. Filet decoded each
 * file itself - tens of milliseconds for a photo and several hundred for a video frame, two at a
 * time - so a first visit to a folder of videos took seconds to fill. The system's preview is a
 * small JPEG already on disk; reading it is a few milliseconds whatever the file is.
 *
 * Used only where it is trustworthy, and decoding stays the answer everywhere else:
 *
 * - **Only a row that matches the file.** The media store can lag the disk: a photo edited in
 *   place keeps its old row until it is scanned again, and its old preview with it. A row whose
 *   size or time differs from the file's is not used - see [SystemThumbRules.fresh].
 * - **Only when it is big enough.** The system's previews are small, and a tall screenshot's is
 *   narrower still. One that would have to be stretched to fill the slot is put back and the file
 *   decoded instead - see [ThumbSize.covers]. Soft previews beside the gallery's were a defect
 *   fixed once already (see [ThumbSize]), and a fast preview that brings it back is no gain.
 * - **Android 10 and up**, where `loadThumbnail` exists. It also turns the picture upright.
 *
 * The rows of a folder are asked for once and kept, keyed by the folder's own time, so a folder
 * of two thousand photos is one query rather than two thousand.
 */
internal object SystemThumbs {

    private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    /** One file's row: what to ask for, and what to check it against. */
    private class Row(val id: Long, val type: Int, val bytes: Long, val modifiedS: Long, val width: Int, val height: Int)

    /** A folder's rows by file name, stamped with the folder's time when they were read. */
    private class Folder(val stamp: Long, val rows: Map<String, Row>)

    private val folders = LruCache<String, Folder>(32)

    private val gate = Any()

    /**
     * The system's preview of the file at [os], brought to nothing smaller than [px] on the edge
     * that matters, or null when there is none worth using. Blocking: a query the first time a
     * folder is seen, then a read of a small file.
     */
    fun load(context: Context, os: String, size: Long, mtime: Long, px: Int, square: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!os.startsWith("/storage/")) return null
        val file = File(os)
        val dir = file.parent ?: return null
        val row = folder(context, dir)?.rows?.get(file.name) ?: return null
        if (!SystemThumbRules.fresh(row.bytes, row.modifiedS, size, mtime)) return null
        val base = when (row.type) {
            IMAGE -> MediaStore.Images.Media.getContentUri("external")
            VIDEO -> MediaStore.Video.Media.getContentUri("external")
            else -> return null
        }
        val box = ThumbSize.boxFor(row.width, row.height, px, square)
        if (box <= 0) return null
        val bitmap = runCatching {
            context.contentResolver.loadThumbnail(ContentUris.withAppendedId(base, row.id), Size(box, box), null)
        }.getOrNull() ?: return null
        if (!ThumbSize.covers(bitmap.width, bitmap.height, px, square)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /** The rows for [dir], read now if they were never read or the folder has changed since. */
    private fun folder(context: Context, dir: String): Folder? {
        val stamp = File(dir).lastModified()
        synchronized(gate) {
            folders.get(dir)?.let { if (it.stamp == stamp) return it }
            val rows = runCatching { query(context, dir) }.getOrNull() ?: return null
            return Folder(stamp, rows).also { folders.put(dir, it) }
        }
    }

    private fun query(context: Context, dir: String): Map<String, Row> {
        val data = MediaStore.MediaColumns.DATA
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            data,
            type,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        // The folder's own files: under it, and not under a folder inside it.
        val selection = "$data LIKE ? ESCAPE '\\' AND $data NOT LIKE ? ESCAPE '\\' AND $type IN ($IMAGE, $VIDEO)"
        val under = SystemThumbRules.likeEscape(dir.trimEnd('/')) + "/"
        val args = arrayOf("$under%", "$under%/%")
        val out = HashMap<String, Row>()
        context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                out[path.substringAfterLast('/')] = Row(
                    id = c.getLong(0),
                    type = c.getInt(2),
                    bytes = c.getLong(3),
                    modifiedS = c.getLong(4),
                    width = c.getInt(5),
                    height = c.getInt(6),
                )
            }
        }
        return out
    }

    /** Forgets every folder, for a test or a low-memory trim. */
    fun clear() = folders.evictAll()
}

/** The decisions [SystemThumbs] makes, kept apart so each is a test. Pure. */
internal object SystemThumbRules {

    /**
     * Whether a media store row still describes the file: the same size, and the same time to the
     * second, which is all the store keeps. A row that differs is from before an edit the store
     * has not caught up with, and its preview is of a picture that no longer exists.
     */
    fun fresh(rowBytes: Long, rowModifiedS: Long, size: Long, mtimeMs: Long): Boolean =
        rowBytes == size && kotlin.math.abs(rowModifiedS - mtimeMs / 1000) <= 1

    /**
     * [text] made literal inside a `LIKE` pattern. A folder named `100%_done` would otherwise
     * match every folder starting `100`, and the wrong folder's previews would be shown for files
     * that happen to share a name.
     */
    fun likeEscape(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
