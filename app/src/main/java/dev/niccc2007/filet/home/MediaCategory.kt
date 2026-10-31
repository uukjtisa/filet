package dev.niccc2007.filet.home

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import dev.niccc2007.filet.playback.AllMusicFilter
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
 * A file deleted outside Filet can also linger in MediaStore until the scanner catches up.
 * Rows are NOT checked for existence as they become nodes: that is two thousand `stat` calls
 * per open, each served by the media provider's FUSE daemon, to catch a case that opening the
 * file reports anyway.
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
    // "Audio", not "Music". The category is MediaStore's AUDIO media type, which is voice
    // notes, recordings, ringtones and podcasts as well as songs - calling it Music promised a
    // library and delivered an inventory.
    AUDIO("audio", "Audio", FiletIcons.Audio),

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
    MediaCategory.DOCUMENTS to listOf("pdf", "docx", "pptx", "xlsx", "xlsm", "ods", "md", "txt", "csv"),
    MediaCategory.ARCHIVES to listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"),
)


/**
 * What reading a category came to. Four outcomes, because they mean four different things to
 * the person looking at the screen and used to be drawn as one.
 *
 * Bug identified here: every failure was swallowed into an empty list, and an empty list is
 * drawn as "Nothing of this kind on the phone's own storage". A query the media provider
 * REJECTED therefore looked exactly like a phone with no videos on it, which is the one reading
 * nobody would think to question.
 */
sealed interface CategoryResult {
    /** Rows found. [unplaced] counts rows that had no path Filet could reconstruct. */
    data class Found(val nodes: List<VNode>, val unplaced: Int = 0) : CategoryResult

    /** The query ran, was allowed to see everything, and matched nothing. Truly empty. */
    data object Empty : CategoryResult

    /** No storage permission, so the media index only shows Filet its own files. */
    data object Refused : CategoryResult

    /** The query itself failed. [reason] is the provider's message, for the screen. */
    data class Failed(val reason: String) : CategoryResult
}

object CategoryQuery {

    /** One track: the file, how long it plays, and when the media index first saw it. */
    /**
     * One track off the media index. [title], [artist] and [album] are the index's own reading of
     * the tags - what a search across a whole library can match without opening two thousand
     * files - and null where the index has none.
     */
    data class AudioRow(
        val node: VNode,
        val durationMs: Long,
        val addedAtMs: Long,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
    )


    /**
     * How many rows to read at most.
     *
     * Two thousand. A phone with forty thousand photos on it would otherwise build forty
     * thousand rows to put in a lazy list that will show about twelve, and the query is only
     * fast because the database does the sorting - asking for all of it moves the cost to the
     * cursor walk. Newest first, so the cut is always at the uninteresting end.
     */
    const val LIMIT = 2000

    /** The rows alone, for callers that only want something to draw. */
    suspend fun read(context: Context, category: MediaCategory, limit: Int = LIMIT): List<VNode> =
        (readResult(context, category, limit) as? CategoryResult.Found)?.nodes.orEmpty()

    /**
     * Read one category, newest first, and say what happened.
     *
     * On IO: a content-provider query and a cursor walk is cheap but is not main-thread work.
     */
    suspend fun readResult(
        context: Context,
        category: MediaCategory,
        limit: Int = LIMIT,
    ): CategoryResult = withContext(Dispatchers.IO) {
        val read = try {
            query(context, category, limit)
        } catch (e: Exception) {
            return@withContext CategoryResult.Failed(e.message ?: e::class.java.simpleName)
        }
        outcome(read.first, read.second, canSeeEverything(context))
    }

    /**
     * Found, empty or refused, from what came back and what Filet was allowed to see.
     *
     * Without a storage permission the media index answers with Filet's OWN files only, and on
     * most phones that is none - so "nothing came back" means refused there, not empty. Pure,
     * so the distinction is a test rather than a judgement made once on one phone.
     */
    internal fun outcome(nodes: List<VNode>, unplaced: Int, permitted: Boolean): CategoryResult = when {
        nodes.isNotEmpty() -> CategoryResult.Found(nodes, unplaced)
        !permitted -> CategoryResult.Refused
        // Rows exist and none could be placed: not empty, and "nothing of this kind" would be
        // the same wrong reading as before in a new place.
        unplaced > 0 -> CategoryResult.Failed("$unplaced files were found but their location could not be read.")
        else -> CategoryResult.Empty
    }

    /** All-files access, or the storage read permission. Either lets the index answer in full. */
    private fun canSeeEverything(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun query(context: Context, category: MediaCategory, limit: Int): Pair<List<VNode>, Int> {
        val uri = MediaStore.Files.getContentUri("external")
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val columns = buildList {
            add(MediaStore.Files.FileColumns.DATA)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            if (modern) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()
        // Filet's own files are not the phone's documents or audio. See FeedExclusions.ownPaths.
        val (selection, args) = selectionFor(category).let { (sel, a) ->
            excluding(sel, a, dev.niccc2007.filet.home.FeedExclusions.ownPaths(dev.niccc2007.filet.data.FiletStore.rootPath(context)))
        }
        val sort = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"

        // THE bug. This used to put `LIMIT 2000` inside the sort-order string, the old trick for
        // a provider query with no limit parameter. From Android 11 the media provider parses
        // its arguments with a strict SQL grammar and rejects that string outright ("Invalid
        // token LIMIT"), so on every phone running 11 or later the query threw, the throw was
        // swallowed, and every category read as empty.
        //
        // The Bundle form is the supported way to say the same thing, and the media provider has
        // honoured QUERY_ARG_LIMIT since 11. Before 11 the provider is the old one, which accepts
        // the sort-order trick and does not read a Bundle limit - so each version gets the form
        // it understands.
        val cursor = limited(context, uri, columns, selection, args, sort, limit)

        val primary = Environment.getExternalStorageDirectory().path
        val out = ArrayList<VNode>(minOf(limit, 256))
        var unplaced = 0
        cursor?.use { c ->
            val dataAt = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val nameAt = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeAt = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val timeAt = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val relAt = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val volAt = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            // Stopped here as well as in the query: a provider that ignored the limit must not
            // turn this into a walk of the whole table.
            while (out.size + unplaced < limit && c.moveToNext()) {
                val node = nodeFor(
                    data = if (dataAt >= 0) c.getString(dataAt) else null,
                    relativePath = if (relAt >= 0) c.getString(relAt) else null,
                    volume = if (volAt >= 0) c.getString(volAt) else null,
                    name = if (nameAt >= 0) c.getString(nameAt) else null,
                    size = if (sizeAt >= 0) c.getLong(sizeAt) else -1L,
                    seconds = if (timeAt >= 0) c.getLong(timeAt) else 0L,
                    primaryRoot = primary,
                )
                if (node == null) unplaced++ else out.add(node)
            }
        }
        return out to unplaced
    }

    /**
     * A sorted, limited query in whichever form this Android version's media provider accepts.
     *
     * One function because both readers need exactly this and the difference between the forms
     * is the bug this file was fixed for - see the note in [query].
     */
    private fun limited(
        context: Context,
        uri: android.net.Uri,
        columns: Array<String>,
        selection: String?,
        args: Array<String>?,
        sort: String,
        limit: Int,
        offset: Int = 0,
    ): android.database.Cursor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bundle = Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                if (offset > 0) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            context.contentResolver.query(uri, columns, bundle, null)
        } else {
            val tail = if (offset > 0) " LIMIT $limit OFFSET $offset" else " LIMIT $limit"
            context.contentResolver.query(uri, columns, selection, args, sort + tail)
        }

    // ── every file, by when it first appeared ──

    /**
     * Be told when the media index changes, until the handle is closed.
     *
     * Here rather than in the feed so that the media index is reached from one file. The
     * caller owns the handle's lifetime and must close it when nobody is looking - see
     * `HomeFeed.watchIndex`.
     */
    fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val uri = MediaStore.Files.getContentUri("external")
        runCatching { context.contentResolver.registerContentObserver(uri, true, observer) }
        return AutoCloseable { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    /** One file and when the media index first recorded it. */
    data class NewRow(val node: VNode, val addedAtMs: Long)

    /**
     * Every file on the phone's own storage, newest first-appearance first, one page at a time.
     *
     * The source of the New files tab. See `HomeFeed` for why `DATE_ADDED` is the clock.
     *
     * @param offset rows to skip, for the next page.
     * @param before when set, only files that appeared before this instant (ms) - the date jump,
     *   which is a range query rather than a scroll through every newer page.
     */
    suspend fun readNew(
        context: Context,
        offset: Int,
        limit: Int,
        before: Long? = null,
        blocked: Set<String> = emptySet(),
        prefixes: Set<String> = emptySet(),
        // No default, for the reason `newRows` gives for `prefixes`: a caller that forgot it
        // would silently get the date-added list in the date-modified tab.
        by: HistorySort,
    ): Pair<CategoryResult, List<NewRow>> = withContext(Dispatchers.IO) {
        val read = try {
            newRows(context, offset, limit, before, blocked, prefixes, by)
        } catch (e: Exception) {
            return@withContext CategoryResult.Failed(e.message ?: e::class.java.simpleName) to emptyList()
        }
        outcome(read.first.map { it.node }, read.second, canSeeEverything(context)) to read.first
    }

    /**
     * The WHERE clause for the New files list, as a selection and its arguments. Pure.
     *
     * - `mime_type IS NOT NULL` drops FOLDERS. The files table holds a row per directory, and a
     *   directory is the one row the media provider gives no type; every file gets one, an
     *   unrecognised one as `application/octet-stream`.
     * - A leading dot is a hidden file, which a list of what arrived should not surface.
     * - [before] is in milliseconds and the column is in SECONDS.
     */
    internal fun newSelection(
        before: Long?,
        blocked: Set<String> = emptySet(),
        prefixes: Set<String> = emptySet(),
        by: HistorySort,
    ): Pair<String, Array<String>> {
        val base = MediaStore.MediaColumns.MIME_TYPE + " IS NOT NULL AND " +
            MediaStore.MediaColumns.DISPLAY_NAME + " NOT LIKE ?"
        val args = mutableListOf(".%")
        var sql = base
        // The date jump's window is on whichever date the list is ordered by. Both columns are
        // in seconds.
        if (before != null) {
            sql += " AND " + columnFor(by) + " < ?"
            args += (before / 1000L).toString()
        }
        // Folders the reader has blocked, excluded by the DATABASE rather than afterwards.
        //
        // It has to be here and not a filter over the rows: the page size and the offset are
        // applied by the provider, so dropping rows after the query would return short pages,
        // make the offset arithmetic wrong, and eventually hide files that are not blocked at
        // all. `escapeLike` exists because a folder named `100%_backup` would otherwise match
        // everything - a path is user data and LIKE has wildcards in it.
        //
        // Each entry is the thing itself AND everything under it. Bug identified here: only the
        // `under` half was written, as `path/%`, so an entry naming a FILE - one hidden from the
        // list by its own path, or Filet's own settings file - matched nothing in the query and
        // came back in every page.
        for ((under, exact) in blockedEntries(blocked)) {
            sql += " AND (" + MediaStore.MediaColumns.DATA + " IS NULL OR (" +
                MediaStore.MediaColumns.DATA + " NOT LIKE ? ESCAPE '\\' AND " +
                MediaStore.MediaColumns.DATA + " != ?))"
            args += under
            args += exact
        }
        // Name prefixes, in the DATABASE for exactly the same reason as the folders above: a
        // filter applied to the rows afterwards returns short pages and corrupts the offset.
        //
        // One pattern per prefix covers both halves of the rule - the entry's own name and any
        // folder it sits under - because every segment of a path is preceded by a separator,
        // so `%/log_%` matches a file called `log_1.txt` and anything inside a folder called
        // `log_old`. That is the subtree rule the feature is actually about: hiding
        // `.thumbnails` while keeping the ten thousand images in it is the worst outcome
        // available. See `FeedExclusions`, which owns the same rule for callers that have a
        // path rather than a query.
        for (pattern in prefixPatterns(prefixes)) {
            sql += " AND (" + MediaStore.MediaColumns.DATA + " IS NULL OR " +
                MediaStore.MediaColumns.DATA + " NOT LIKE ? ESCAPE '\\')"
            args += pattern
        }
        return sql to args.toTypedArray()
    }

    /**
     * The LIKE patterns for a set of name prefixes.
     *
     * `%/p%` rather than `p%`: anchored to a separator so it matches the start of ANY segment
     * and never the middle of one. Without the separator, excluding `log_` would also exclude
     * `catalog_2026.txt`, and somebody filtering their logs would silently lose their
     * catalogue.
     *
     * Escaped, because a prefix is typed by a person and `%` and `_` are wildcards - `log_` has
     * one in it already, which is why this is not a theoretical concern. A blank entry is
     * dropped rather than turned into `%/%`, which would hide every file on the device.
     */
    internal fun prefixPatterns(prefixes: Set<String>): List<String> =
        prefixes.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "%/" + escapeLike(it) + "%" }
            .distinct()
            .toList()

    /**
     * The LIKE patterns for a set of blocked folders: the folder itself and everything under it.
     *
     * Trailing separators are trimmed first, so `/DCIM/` and `/DCIM` are one folder rather than
     * two patterns that disagree, and the result is de-duplicated after trimming - the set is
     * keyed on the raw string, so two spellings of one folder arrive as two entries and would
     * otherwise each add a clause that says the same thing. A blank entry is dropped rather than
     * turned into `%`, which would block the entire device - a stored preference is not
     * trustworthy just because this code writes it carefully.
     */
    internal fun blockedPrefixes(blocked: Set<String>): List<String> = blockedEntries(blocked).map { it.first }

    /** For each blocked entry, the LIKE pattern for what is under it and the entry's own path. */
    internal fun blockedEntries(blocked: Set<String>): List<Pair<String, String>> =
        blocked.asSequence()
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() && it != "/" }
            .map { escapeLike(it) + "/%" to it }
            .distinct()
            .toList()

    /**
     * [selection] with each of [paths] left out, itself and everything under it. Pure.
     *
     * The selection is bracketed first: a category's own selection can be a run of ORs, and an
     * AND appended to one binds to its last term only - which would apply the exclusion to one
     * kind of file in the category and not the others.
     */
    internal fun excluding(selection: String, args: Array<String>, paths: Set<String>): Pair<String, Array<String>> {
        val entries = blockedEntries(paths)
        if (entries.isEmpty()) return selection to args
        val data = MediaStore.MediaColumns.DATA
        var sql = "(" + selection + ")"
        val out = args.toMutableList()
        for ((under, exact) in entries) {
            sql += " AND (" + data + " IS NULL OR (" + data + " NOT LIKE ? ESCAPE '\\' AND " + data + " != ?))"
            out += under
            out += exact
        }
        return sql to out.toTypedArray()
    }

    /**
     * The `WHERE` for All Music, built from the filter's decision.
     *
     * Here rather than beside the decision itself: this is the layer that owns MediaStore, and
     * naming its columns is query building. See `AllMusicFilter` for why the list is filtered
     * at all and what the defaults mean.
     *
     * The folders are one `OR`-ed group, so a file qualifies by being in ANY of them, and the
     * lengths are `AND`-ed around it. An empty folder list means no folder restriction rather
     * than no results - "scan nowhere" is never what clearing the field meant, and a library
     * that silently empties itself is the worst reading of an ambiguous setting.
     */
    internal fun audioSelection(
        folders: Collection<String>,
        primary: String,
        minMs: Long,
        maxMs: Long,
    ): Pair<String?, Array<String>> {
        val args = mutableListOf<String>()
        val clauses = mutableListOf<String>()

        val dirs = AllMusicFilter.prefixes(folders, primary)
        if (dirs.isNotEmpty()) {
            clauses += dirs.joinToString(" OR ", prefix = "(", postfix = ")") {
                args += escapeLike(it) + "/%"
                MediaStore.MediaColumns.DATA + " LIKE ? ESCAPE '\\'"
            }
        }

        val lo = minMs.coerceAtLeast(0L)
        val hi = if (maxMs <= 0L) 0L else maxMs
        val bounds = mutableListOf<String>()
        if (lo > 0L) {
            bounds += MediaStore.Audio.AudioColumns.DURATION + " >= ?"
            args += lo.toString()
        }
        // A ceiling of zero means "no ceiling", which is how the control says "any" at the top
        // of its travel. A ceiling BELOW the floor would match nothing, so it is ignored rather
        // than obeyed - either control can be dragged past the other by accident.
        if (hi > 0L && hi > lo) {
            bounds += MediaStore.Audio.AudioColumns.DURATION + " <= ?"
            args += hi.toString()
        }
        if (bounds.isNotEmpty()) {
            // A duration of 0 is MediaStore saying it does not KNOW, not a zero-length file, so
            // those rows survive both bounds. Dropping them would hide real tracks whose
            // headers the index never read, and a missing album is a worse outcome than a
            // stray tone.
            clauses += "((" + bounds.joinToString(" AND ") + ") OR " +
                MediaStore.Audio.AudioColumns.DURATION + " <= 0)"
        }

        if (clauses.isEmpty()) return null to emptyArray()
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    /** Escape the wildcards LIKE treats specially, so a path matches itself and nothing more. */
    internal fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Newest first; `_id` breaks ties so a page boundary never splits a second unstably. */
    internal const val NEW_ORDER = "date_added DESC, _id DESC"

    /** Newest first by date modified, with the same `_id` tiebreak so paging is stable. */
    internal const val MODIFIED_ORDER = "date_modified DESC, _id DESC"

    internal fun orderFor(by: HistorySort): String = when (by) {
        HistorySort.DATE_ADDED -> NEW_ORDER
        HistorySort.DATE_MODIFIED -> MODIFIED_ORDER
    }

    internal fun columnFor(by: HistorySort): String = when (by) {
        HistorySort.DATE_ADDED -> MediaStore.MediaColumns.DATE_ADDED
        HistorySort.DATE_MODIFIED -> MediaStore.MediaColumns.DATE_MODIFIED
    }

    private fun newRows(
        context: Context,
        offset: Int,
        limit: Int,
        before: Long?,
        blocked: Set<String>,
        // No default. A default here is what let the prefixes be dropped silently: the caller
        // below simply did not pass them, the compiler was satisfied, and the rule reached the
        // query as an empty set. Plumbing that is always supplied should refuse to compile
        // when it is not.
        prefixes: Set<String>,
        by: HistorySort,
    ): Pair<List<NewRow>, Int> {
        val uri = MediaStore.Files.getContentUri("external")
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val columns = buildList {
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (modern) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()
        val (selection, args) = newSelection(before, blocked, prefixes, by)
        val primary = Environment.getExternalStorageDirectory().path
        val out = ArrayList<NewRow>(minOf(limit, 512))
        var unplaced = 0
        limited(context, uri, columns, selection, args, orderFor(by), limit, offset)?.use { c ->
            val dataAt = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameAt = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeAt = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val timeAt = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedAt = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val relAt = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val volAt = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            while (out.size + unplaced < limit && c.moveToNext()) {
                val node = nodeFor(
                    data = if (dataAt >= 0) c.getString(dataAt) else null,
                    relativePath = if (relAt >= 0) c.getString(relAt) else null,
                    volume = if (volAt >= 0) c.getString(volAt) else null,
                    name = if (nameAt >= 0) c.getString(nameAt) else null,
                    size = if (sizeAt >= 0) c.getLong(sizeAt) else -1L,
                    seconds = if (timeAt >= 0) c.getLong(timeAt) else 0L,
                    primaryRoot = primary,
                )
                if (node == null) {
                    unplaced++
                    continue
                }
                // SECONDS, like DATE_MODIFIED.
                val added = if (addedAt >= 0) c.getLong(addedAt) * 1000L else 0L
                out += NewRow(node, added)
            }
        }
        return out to unplaced
    }

    // ── music, for the All Music playlist ──

    /**
     * The column a music list is ordered by, from a KEY: `NAME`, `ADDED`, `MODIFIED`, `SIZE` or
     * `DURATION`.
     *
     * A key rather than a column for two reasons. The order string goes into SQL inside the
     * media provider, so it is only ever one of these five constants and never text a caller
     * supplied. And the caller is the player, which may not name MediaStore columns at all -
     * storage APIs stay out of `playback/`, see `check-r3`.
     *
     * An unknown key falls back to date added rather than throwing. A caller passing a wrong key
     * is a bug, but there it would be a crash in the playlist tab; newest first is a sensible
     * list and the wrong order is visible enough to be reported.
     */
    internal fun audioColumn(key: String): String = when (key) {
        "NAME" -> MediaStore.MediaColumns.DISPLAY_NAME
        "MODIFIED" -> MediaStore.MediaColumns.DATE_MODIFIED
        "SIZE" -> MediaStore.MediaColumns.SIZE
        "DURATION" -> MediaStore.Audio.AudioColumns.DURATION
        else -> MediaStore.MediaColumns.DATE_ADDED
    }

    /**
     * Every track on the phone's own storage, ordered by the DATABASE.
     *
     * Ordered before the limit applies, which is the point of taking the column here: sorting
     * the newest two thousand rows by name in Kotlin sorts the wrong two thousand on any phone
     * holding more than that.
     *
     * The audio collection rather than the files table with a media-type clause. It is the
     * same rows - the collection IS the files whose media type is audio, decided by the
     * scanner, never by an extension list - and it carries `DURATION` on every Android version
     * this app supports, where the files table only promises it from 10.
     *
     * @param orderColumn a key, see [audioColumn]: `NAME`, `ADDED`, `MODIFIED`, `SIZE`, `DURATION`.
     * @return the outcome, as [readResult] reports it, and the rows when there are any.
     */
    suspend fun readAudio(
        context: Context,
        orderColumn: String,
        descending: Boolean,
        limit: Int = LIMIT,
        /** Which folders and lengths qualify. See `AllMusicFilter`. */
        folders: Collection<String> = emptyList(),
        minMs: Long = 0L,
        maxMs: Long = 0L,
    ): Pair<CategoryResult, List<AudioRow>> {
        return withContext(Dispatchers.IO) {
            val read = try {
                audio(context, orderColumn, descending, limit, folders, minMs, maxMs)
            } catch (e: Exception) {
                return@withContext CategoryResult.Failed(e.message ?: e::class.java.simpleName) to emptyList()
            }
            outcome(read.first.map { it.node }, read.second, canSeeEverything(context)) to read.first
        }
    }

    /**
     * How many tracks All Music holds under its filter, or null when the index will not say.
     *
     * The same selection as [readAudio], so the count on the Playlists banner and the list it
     * opens cannot disagree. One column and no order: the provider answers a count without
     * building rows, which is why this is not `readAudio(...).size` with a huge limit.
     */
    suspend fun countAudio(
        context: Context,
        folders: Collection<String> = emptyList(),
        minMs: Long = 0L,
        maxMs: Long = 0L,
    ): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val primary = Environment.getExternalStorageDirectory().path
            val (selection, args) = audioSelection(folders, primary, minMs, maxMs)
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null,
            )?.use { it.count }
        }.getOrNull()
    }

    /**
     * The ORDER BY for a music list. Pure, for the test.
     *
     * Names fold case - `COLLATE NOCASE` - or every lower-case title sorts after every upper-case
     * one, which reads as a broken alphabet. `_id` breaks ties, so two tracks with the same
     * name or size keep one order between reads instead of swapping on every refresh.
     */
    /** What the media index writes for a tag it does not have. */
    private const val UNKNOWN_TAG = "<unknown>"

    internal fun audioOrder(orderColumn: String, descending: Boolean): String {
        val column = audioColumn(orderColumn)
        val dir = if (descending) "DESC" else "ASC"
        val col = if (column == MediaStore.MediaColumns.DISPLAY_NAME) "$column COLLATE NOCASE" else column
        return "$col $dir, ${MediaStore.MediaColumns._ID} $dir"
    }

    private fun audio(
        context: Context,
        orderColumn: String,
        descending: Boolean,
        limit: Int,
        folders: Collection<String>,
        minMs: Long,
        maxMs: Long,
    ): Pair<List<AudioRow>, Int> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val columns = buildList {
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.Audio.AudioColumns.DURATION)
            add(MediaStore.Audio.AudioColumns.TITLE)
            add(MediaStore.Audio.AudioColumns.ARTIST)
            add(MediaStore.Audio.AudioColumns.ALBUM)
            if (modern) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            }
        }.toTypedArray()
        val primary = Environment.getExternalStorageDirectory().path
        // Filtered in the DATABASE, not over the rows: the limit is applied by the provider, so
        // dropping rows afterwards would return a short list and call it the whole library.
        val (selection, args) = audioSelection(folders, primary, minMs, maxMs)
        val out = ArrayList<AudioRow>(minOf(limit, 256))
        var unplaced = 0
        limited(context, uri, columns, selection, args, audioOrder(orderColumn, descending), limit)?.use { c ->
            val dataAt = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameAt = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeAt = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val timeAt = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedAt = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val lenAt = c.getColumnIndex(MediaStore.Audio.AudioColumns.DURATION)
            val titleAt = c.getColumnIndex(MediaStore.Audio.AudioColumns.TITLE)
            val artistAt = c.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST)
            val albumAt = c.getColumnIndex(MediaStore.Audio.AudioColumns.ALBUM)
            val relAt = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val volAt = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            while (out.size + unplaced < limit && c.moveToNext()) {
                val node = nodeFor(
                    data = if (dataAt >= 0) c.getString(dataAt) else null,
                    relativePath = if (relAt >= 0) c.getString(relAt) else null,
                    volume = if (volAt >= 0) c.getString(volAt) else null,
                    name = if (nameAt >= 0) c.getString(nameAt) else null,
                    size = if (sizeAt >= 0) c.getLong(sizeAt) else -1L,
                    seconds = if (timeAt >= 0) c.getLong(timeAt) else 0L,
                    primaryRoot = primary,
                )
                if (node == null) {
                    unplaced++
                    continue
                }
                out += AudioRow(
                    node = node,
                    // DURATION is already milliseconds. DATE_ADDED is SECONDS, the same trap as
                    // DATE_MODIFIED - see [nodeFor].
                    durationMs = if (lenAt >= 0) c.getLong(lenAt) else 0L,
                    addedAtMs = if (addedAt >= 0) c.getLong(addedAt) * 1000L else 0L,
                    title = if (titleAt >= 0) c.getString(titleAt)?.takeIf { it.isNotBlank() } else null,
                    // "<unknown>" is the index's way of saying it has none, not an artist.
                    artist = if (artistAt >= 0) c.getString(artistAt)?.takeIf { it.isNotBlank() && it != UNKNOWN_TAG } else null,
                    album = if (albumAt >= 0) c.getString(albumAt)?.takeIf { it.isNotBlank() && it != UNKNOWN_TAG } else null,
                )
            }
        }
        return out to unplaced
    }

    /**
     * One row as a node, or null when no path can be made of it.
     *
     * `DATA` first: deprecated for writing, still filled in for reading on every version this
     * app supports, and the exact path. When a provider leaves it empty, the path is rebuilt from
     * `RELATIVE_PATH` and `DISPLAY_NAME` on the row's volume, which keeps the node an ordinary
     * file the VFS can open, move and reveal in its folder. A `content://` URI instead would
     * open but could not be revealed, renamed or sorted into a folder, because it is not
     * anywhere.
     *
     * The cost of the rebuild is the volume. The primary volume is [primaryRoot]; any other is
     * assumed to mount at `/storage/<ID>`, the provider's lower-case id written upper-case. That
     * is how removable cards mount on every phone this has met, and is a convention rather than
     * a promise.
     */
    internal fun nodeFor(
        data: String?,
        relativePath: String?,
        volume: String?,
        name: String?,
        size: Long,
        seconds: Long,
        primaryRoot: String,
    ): VNode? {
        val path = data?.takeIf { it.isNotBlank() }
            ?: rebuild(relativePath, volume, name, primaryRoot)
            ?: return null
        return VNode(
            path = VPath.of("local", path),
            isDir = false,
            size = size,
            // DATE_MODIFIED is in SECONDS. Treating it as milliseconds dates every file to 1970
            // and puts the whole list in the wrong order, the most common mistake against this
            // table.
            mtime = seconds * 1000L,
        )
    }

    /** `external_primary` and `external`, by value so the pure path runs off the device. */
    private val PRIMARY_VOLUMES = setOf("external_primary", "external")

    private fun rebuild(relativePath: String?, volume: String?, name: String?, primaryRoot: String): String? {
        if (name.isNullOrBlank() || relativePath == null) return null
        val root = if (volume.isNullOrEmpty() || volume in PRIMARY_VOLUMES) {
            primaryRoot.trimEnd('/')
        } else {
            "/storage/" + volume.uppercase(java.util.Locale.US)
        }
        val rel = relativePath.trim('/')
        return if (rel.isEmpty()) "$root/$name" else "$root/$rel/$name"
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
                // One LIKE per extension, OR'd and bracketed so the clause stays one term if the
                // provider ANDs its own conditions onto it. DISPLAY_NAME rather than DATA, so a
                // folder called "Documents" does not drag in everything inside it.
                val clause = exts.joinToString(" OR ", "(", ")") {
                    MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?"
                }
                clause to exts.map { "%.$it" }.toTypedArray()
            }
        }
    }
}
