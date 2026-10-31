package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two dates the media index keeps about a file, for the Details sheet.
 *
 * Two different facts, kept apart on purpose:
 * - [added] is when the file arrived on THIS phone, as the system recorded it. It is one input
 *   to "On this phone since".
 * - [taken] is when a photo or video was made, wherever that was. A picture somebody sent
 *   yesterday can have been taken years ago, so it is never mixed into the arrival date; it is
 *   its own row.
 *
 * Here rather than above the VFS because it is a MediaStore query, which PLAN.md R3 keeps in
 * the provider layer. It takes an OS path - what `Vfs.osPath` answers for a local file - and
 * answers null for anything the media index has no row for.
 */
object MediaDates {

    data class Dates(val added: Long?, val taken: Long?)

    /** The file's own row. */
    suspend fun ofFile(context: Context, osPath: String): Dates? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN),
                MediaStore.MediaColumns.DATA + " = ?",
                arrayOf(osPath),
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                // DATE_ADDED is in seconds, DATE_TAKEN in milliseconds - the media index's own
                // inconsistency, not a typo here.
                val added = if (c.isNull(0)) null else c.getLong(0) * 1000L
                val taken = if (c.isNull(1)) null else c.getLong(1).takeIf { it > 0 }
                Dates(added?.takeIf { it > 0 }, taken)
            }
        }.getOrNull()
    }

    /**
     * For a folder: the earliest arrival of the folder's own row or anything beneath it.
     *
     * A folder was here at least as early as the first thing put in it, which is why the
     * contents count as evidence about the folder.
     */
    suspend fun earliestUnder(context: Context, osDir: String): Long? = withContext(Dispatchers.IO) {
        val base = osDir.trimEnd('/')
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATE_ADDED),
                "(" + MediaStore.MediaColumns.DATA + " = ? OR " + MediaStore.MediaColumns.DATA + " LIKE ? ESCAPE '\\') AND " +
                    MediaStore.MediaColumns.DATE_ADDED + " > 0",
                arrayOf(base, escapeLike(base) + "/%"),
                MediaStore.MediaColumns.DATE_ADDED + " ASC",
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) * 1000L else null }
        }.getOrNull()
    }

    private fun escapeLike(s: String): String = buildString {
        for (ch in s) {
            if (ch == '%' || ch == '_' || ch == '\\') append('\\')
            append(ch)
        }
    }
}
