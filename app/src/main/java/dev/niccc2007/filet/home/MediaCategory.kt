package dev.niccc2007.filet.home

import android.content.Context
import android.provider.MediaStore
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything of one kind, from the whole phone. Issue #4.
 *
 * ## What was asked for
 *
 * Somewhere to open all the photos at once, and the same for audio, video, packages and
 * documents - in those words, so that a file manager can replace the gallery and the music
 * player rather than sitting beside them. Every other file manager has this.
 *
 * ## Why MediaStore and not Filet's own index
 *
 * Filet maintains a search index, and the obvious move is to query it. Two things decide
 * against it. The index is a SETTING - it can be switched off, and a headline feature that is
 * empty until somebody turns on a database is a feature most people will conclude is broken.
 * And it has to be crawled: on a phone Filet was installed on an hour ago, the index knows
 * whatever it has got round to, where MediaStore has known since the files were written.
 *
 * MediaStore is the system's own media index. It is already populated, already maintained by
 * the media scanner, and a query over it is a few milliseconds for tens of thousands of rows
 * because it is a real database with real indexes. It is also what every other file manager on
 * Android uses for exactly this screen.
 *
 * ## What it therefore does NOT see, which is worth being honest about
 *
 * MediaStore covers shared storage. It does not index an app's private directory, anything on
 * a SAF volume Filet reaches through a document provider, a remote share, or the inside of an
 * archive. So these lists are "everything on the phone's own storage", which is what somebody
 * means by "all my photos", and not literally every byte Filet can reach.
 *
 * A file deleted outside Filet can also linger in MediaStore until the scanner catches up, so
 * rows are checked for existence as they are turned into nodes rather than trusted.
 */
enum class MediaCategory(
    val id: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    /**
     * Photos, by MediaStore's own media type rather than by extension.
     *
     * The media type is the system's answer to "is this an image", and it is better than an
     * extension list: it covers the formats a given Android version happens to support,
     * including whatever a camera writes that nobody has heard of yet.
     */
    IMAGES("images", "Photos", FiletIcons.Image),
    VIDEOS("videos", "Videos", FiletIcons.Video),
    AUDIO("audio", "Music", FiletIcons.Audio),

    /**
     * Packages, by extension.
     *
     * No media type covers these, and the bundle formats - xapk, apkm, apks - have no
     * registered mime type at all, so the only thing to match on is the name. See
     * `AndroidManifest.xml`, which has the same problem from the other direction.
     */
    APPS("apps", "Packages", FiletIcons.Apk),

    /** Documents, which is a list of extensions because "document" is not a media type. */
    DOCUMENTS("documents", "Documents", FiletIcons.Doc),

    /** And archives, for the same reason. */
    ARCHIVES("archives", "Archives", FiletIcons.Zip);

    companion object {
        fun of(id: String?): MediaCategory? = entries.firstOrNull { it.id == id }
    }
}

/** Extensions for the categories MediaStore has no media type for. */
private val BY_EXTENSION: Map<MediaCategory, List<String>> = mapOf(
    MediaCategory.APPS to listOf("apk", "xapk", "apkm", "apks", "aab"),
    MediaCategory.DOCUMENTS to listOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "rtf", "txt", "md", "csv", "epub",
    ),
    MediaCategory.ARCHIVES to listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"),
)

object CategoryQuery {

    /**
     * How many rows to read at most.
     *
     * Two thousand. A phone with forty thousand photos on it would otherwise build forty
     * thousand rows to put in a lazy list that will show about twelve, and the query is only
     * fast because the database does the sorting - asking for all of it moves the cost to the
     * cursor walk. Newest first, so the cut is always at the uninteresting end.
     */
    const val LIMIT = 2000

    /**
     * Read one category, newest first.
     *
     * On IO, and it has to be: this is a content-provider query and a cursor walk, which is
     * cheap but is not free and is certainly not main-thread work.
     */
    suspend fun read(
        context: Context,
        category: MediaCategory,
        limit: Int = LIMIT,
    ): List<VNode> = withContext(Dispatchers.IO) {
        runCatching { query(context, category, limit) }.getOrDefault(emptyList())
    }

    private fun query(context: Context, category: MediaCategory, limit: Int): List<VNode> {
        val uri = MediaStore.Files.getContentUri("external")
        val columns = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val (selection, args) = selectionFor(category)
        // Sorted and limited by the DATABASE. Doing either in Kotlin means reading every row
        // on the phone into memory first, which is the difference between a few milliseconds
        // and a visible stall on a device with a big camera roll.
        //
        // The LIMIT clause goes in the sort-order string because the platform's query() has no
        // limit parameter until API 30's Bundle form, and this has to work on Android 8.
        val order = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC LIMIT " + limit
        val out = ArrayList<VNode>(minOf(limit, 256))
        context.contentResolver.query(uri, columns, selection, args, order)?.use { c ->
            val dataAt = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val nameAt = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeAt = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val timeAt = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val path = if (dataAt >= 0) c.getString(dataAt) else null
                if (path.isNullOrBlank()) continue
                val name = (if (nameAt >= 0) c.getString(nameAt) else null)
                    ?: path.substringAfterLast('/')
                val size = if (sizeAt >= 0) c.getLong(sizeAt) else -1L
                // DATE_MODIFIED is in SECONDS. Treating it as milliseconds dates every file to
                // 1970 and puts the whole list in the wrong order, which is the single most
                // common mistake made against this table.
                val seconds = if (timeAt >= 0) c.getLong(timeAt) else 0L
                out.add(
                    VNode(
                        path = VPath.of("local", path),
                        isDir = false,
                        size = size,
                        mtime = seconds * 1000L,
                    ),
                )
            }
        }
        return out
    }

    /**
     * The WHERE clause for a category, as a selection and its arguments.
     *
     * Parameterised, never interpolated. These strings end up in SQL inside the media provider,
     * and an extension list built by string concatenation is an injection waiting for the day
     * somebody makes the list configurable.
     *
     * Internal so the shape can be asserted in a test without a content provider: the media
     * types and the number of placeholders are both easy to get wrong and silent when wrong.
     */
    internal fun selectionFor(category: MediaCategory): Pair<String, Array<String>> {
        val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
        return when (category) {
            MediaCategory.IMAGES -> mediaType + "=?" to
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())

            MediaCategory.VIDEOS -> mediaType + "=?" to
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())

            MediaCategory.AUDIO -> mediaType + "=?" to
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())

            else -> {
                val exts = BY_EXTENSION[category].orEmpty()
                // One LIKE per extension, OR'd. Matching on DISPLAY_NAME rather than DATA so a
                // folder called "Documents" does not drag in everything inside it.
                val clause = exts.joinToString(" OR ") {
                    MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?"
                }
                clause to exts.map { "%.$it" }.toTypedArray()
            }
        }
    }
}
