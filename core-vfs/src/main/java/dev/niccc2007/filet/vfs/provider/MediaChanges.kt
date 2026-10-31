package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * What the media store can say about what changed, and when. SEARCH.md §14.6.
 *
 * Here rather than above the VFS because every call is a MediaStore query, which PLAN.md R3
 * keeps in the provider layer. Callers get OS paths - what `Vfs.osPath` answers for a local
 * file - and never a cursor or a URI.
 */
object MediaChanges {

    /** One row of the media store, for a first build of the index. */
    data class Row(val osPath: String, val isDir: Boolean, val size: Long, val mtime: Long)

    /** Whether generations exist on this phone. They arrived with Android 11. */
    val hasGenerations: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Each external volume's current generation - internal storage and every card - or empty
     * where generations do not exist.
     */
    fun generations(context: Context): Map<String, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        return runCatching {
            MediaStore.getExternalVolumeNames(context).associateWith { MediaStore.getGeneration(context, it) }
        }.getOrDefault(emptyMap())
    }

    /**
     * The OS path of every row on [volume] added or changed after generation [since].
     *
     * Deletions are not in it - a deleted row is gone from the table it would be read from. A
     * search result for a file deleted while Filet was away is caught by its own check and
     * forgotten then (SEARCH.md §14.3).
     *
     * @return null when the query failed. Not an empty answer: a caller that reads "nothing
     *   changed" from a failure records the generation as read and the changes are never seen.
     */
    fun changedSince(context: Context, volume: String, since: Long): Sequence<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val out = ArrayList<String>()
        val ok = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(volume),
                arrayOf(MediaStore.MediaColumns.DATA),
                MediaStore.MediaColumns.GENERATION_MODIFIED + " > ? OR " +
                    MediaStore.MediaColumns.GENERATION_ADDED + " > ?",
                arrayOf(since.toString(), since.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let(out::add)
            } ?: error("no cursor")
        }.isSuccess
        return if (ok) out.asSequence() else null
    }

    /**
     * Every row the media store holds, for an index that has nothing yet.
     *
     * `format` 0x3001 is MTP's "association", which is how the media store marks a folder. The
     * column is not in the public constants, so it is asked for by name and the query is retried
     * without it if a build refuses - every row is then a file, and the folders are still made
     * from the paths above them.
     */
    fun everything(context: Context): Sequence<Row> {
        val uri = MediaStore.Files.getContentUri("external")
        val base = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = ArrayList<Row>()
        fun read(withFormat: Boolean): Boolean = runCatching {
            context.contentResolver.query(uri, if (withFormat) base + "format" else base, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val dir = withFormat && !c.isNull(3) && c.getInt(3) == FORMAT_ASSOCIATION
                    // DATE_MODIFIED is in seconds; the index keeps milliseconds.
                    out += Row(path, dir, if (c.isNull(1)) 0L else c.getLong(1), if (c.isNull(2)) 0L else c.getLong(2) * 1000L)
                }
            }
            true
        }.getOrDefault(false)
        if (!read(withFormat = true)) {
            out.clear()
            read(withFormat = false)
        }
        return out.asSequence()
    }

    /**
     * Call [onChange] whenever anything under shared storage changes, until the returned handle
     * is closed. On the main looper; [onChange] must only schedule work, never do it.
     */
    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        runCatching { context.contentResolver.registerContentObserver(AndroidStorage.mediaChangeUri, true, observer) }
        return AutoCloseable { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    private const val FORMAT_ASSOCIATION = 0x3001
}
