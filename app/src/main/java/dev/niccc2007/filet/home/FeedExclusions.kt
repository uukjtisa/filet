package dev.niccc2007.filet.home

/**
 * What New files leaves out: whole folders, and anything whose name matches a line of the list.
 *
 * ## One star at an end, and not a pattern
 *
 * A line is a whole name (`.thumbnails`), a start (`log_*`), an end (`*.ft_lnk`) or a piece
 * (`*cache*`). The star says which end is open, and that is the whole syntax. A glob or a
 * regular expression would cover more and add several hundred ways to write a rule that
 * silently matches nothing - and a rule that matches nothing looks exactly like a rule that is
 * working, because the feed it is filtering is full either way.
 *
 * Decision: an unmarked line is a WHOLE name, and a start needs its star. The first shape of
 * this list matched only starts, with no marker, so `logs` also hid `logs2024` and an end such
 * as `.ft_lnk` could not be named at all. A list stored in that shape is read through
 * [fromPrefixes], which adds the star each line was implicitly carrying, so nothing that was
 * hidden comes back.
 *
 * ## The rule that makes it worth having
 *
 * A folder that is excluded excludes EVERYTHING BENEATH IT. `.thumbnails` is not one entry to
 * hide, it is ten thousand, and a rule that hid the folder and kept its contents would be the
 * most annoying possible outcome: the thing you asked to lose is gone and the reason you asked
 * is still there.
 *
 * That is also the part that can be silently wrong, which is why this is a pure function with
 * tests rather than a condition inside a query. A subtree test written against a path is one
 * missing separator away from excluding `Downloads` because somebody excluded `Down`.
 */
object FeedExclusions {

    /**
     * Prefixes somebody might reasonably want, offered as chips rather than applied.
     *
     * The ones not in [DEFAULT_RULES] stay offers: `log_` or `tmp_` can be a real file
     * somebody made, and a list that quietly hid those from the first launch would be
     * impossible to tell apart from a list that was not working.
     */
    val SUGGESTED = listOf(".*", "log_*", "tmp_*", "cache_*", "logs")

    /**
     * What New files leaves out on a fresh install, before anything has been typed.
     *
     * Decision: a default value, which the rest of this file argues against, because it is not
     * a file of the person's own. A leading `.` is the platform's mark for "not for people" -
     * `.thumbnails`, `.trashed-1699`, `.nomedia` - and those are the bulk of what floods the
     * list. It is listed in the settings box from the first launch, so nothing is hidden from the
     * person who wants it back - which is the failure the no-defaults rule exists to stop.
     *
     * Bug identified here, in the second shape of this: the files Filet writes at the root of its
     * own folder - the settings file, the two lists beside it and the note explaining the folder -
     * were defaults HERE, as name prefixes. A prefix matches a name anywhere on the phone, so a
     * `playlists.json` of the person's own in Download was hidden too; and a phone whose list was
     * stored before one of them existed never hid that one at all. They are exact paths in
     * [ownPaths] now, which reaches the four files and nothing else, and is never stored.
     *
     * Bug identified here: a first attempt put these in the settings BOX as its placeholder
     * text when the stored set was empty. The box showed them; the feed, which reads the stored
     * set, applied none of them, so a fresh install listed every dot-file while Settings claimed
     * they were hidden. The default belongs to the preference, where the feed reads it.
     */
    val DEFAULT_RULES: Set<String> = linkedSetOf(".*", FILET_LINKS)

    /**
     * Filet's shortcut files, a pointer at a folder or file rather than something that arrived.
     * A rule in the list rather than one of [ownPaths]: they sit in the person's own folders,
     * wherever they were made, and a rule in the box can be taken out by somebody who wants them.
     */
    const val FILET_LINKS = "*.ft_lnk"

    /**
     * A list stored when every line was a start, in the shape it has now.
     *
     * Each line gets the star it was implicitly carrying, so nothing hidden before is shown now,
     * and the shortcut-file rule is added once, since the list predates it.
     */
    fun fromPrefixes(prefixes: Set<String>): Set<String> =
        prefixes.asSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.endsWith('*')) it else "$it*" }
            .plus(FILET_LINKS)
            .toCollection(LinkedHashSet())

    /** How a line of the list compares with a name. */
    enum class Match { NAME, START, END, PIECE }

    /** One line of the list, read. */
    data class Rule(val text: String, val match: Match) {
        fun hits(name: String): Boolean = when (match) {
            Match.NAME -> name == text
            Match.START -> name.startsWith(text)
            Match.END -> name.endsWith(text)
            Match.PIECE -> name.contains(text)
        }
    }

    /**
     * [line] as a rule, or null for a line that names nothing - blank, or only stars. Null rather
     * than a rule that matches everything: `*` alone would empty the whole feed.
     */
    fun rule(line: String): Rule? {
        val t = line.trim()
        val open = t.startsWith('*')
        val close = t.endsWith('*')
        val text = t.trim('*')
        if (text.isEmpty()) return null
        val match = when {
            open && close -> Match.PIECE
            close -> Match.START
            open -> Match.END
            else -> Match.NAME
        }
        return Rule(text, match)
    }

    /**
     * Filet's own folders and the files at the root of its folder, left out of New files and the
     * categories always.
     *
     * The ONE case where hiding by default is right, and it is worth saying why, because the
     * rule everywhere else in this file is the opposite: a feed that hides things from the
     * first launch is indistinguishable from a feed that is broken.
     *
     * This is different because these are not the user's files - they are Filet's own working
     * folders and its own settings, created by the app, and a file manager reporting its own
     * cache writes and log files as "new files on your phone", or listing its settings file
     * under Documents, is the app talking about itself. Applied beside the person's own lists
     * rather than stored in them, so clearing those cannot bring the app's files back in.
     *
     * Each entry is excluded with everything under it, and a FILE entry is the file alone: the
     * test is [under], which counts a path as under itself. `Shared`, `Received` and
     * `Extracted-Apks` are deliberately NOT here - they hold real files that arrived from
     * somewhere else or that the person asked for, which is exactly what New files is for.
     */
    fun ownPaths(filetRoot: String): Set<String> = setOf(
        "$filetRoot/Logs",
        "$filetRoot/Reports",
        "$filetRoot/Scripts",
        "$filetRoot/Memory",
        "$filetRoot/Settings",
        "$filetRoot/.cache",
        "$filetRoot/" + dev.niccc2007.filet.data.ConfigSync.SETTINGS_FILE,
        "$filetRoot/" + dev.niccc2007.filet.data.ConfigSync.BOOKMARKS_FILE,
        "$filetRoot/" + dev.niccc2007.filet.data.ConfigSync.PLAYLISTS_FILE,
        "$filetRoot/" + dev.niccc2007.filet.data.FiletStore.NOTE_FILE,
    )

    /**
     * Whether [path] survives.
     *
     * @param path an absolute path, with `/` separators.
     * @param folders absolute paths of folders to exclude, with everything under them.
     * @param lines the list's lines, read by [rule]; a segment one of them hits excludes that
     *   entry and, when the segment is a folder in the path, everything inside it.
     */
    fun allows(path: String, folders: Set<String>, lines: Set<String>): Boolean {
        val clean = path.replace('\\', '/').trimEnd('/')
        if (clean.isEmpty()) return true
        for (folder in folders) {
            if (under(clean, folder.replace('\\', '/').trimEnd('/'))) return false
        }
        if (lines.isEmpty()) return true
        val rules = lines.mapNotNull(::rule)
        if (rules.isEmpty()) return true
        // EVERY segment, not just the last.
        //
        // This is the subtree rule, and doing it per segment is what gets it for free: a file
        // deep inside `.thumbnails` has `.thumbnails` as one of its segments, so excluding the
        // folder and excluding its contents are the same test. Checking only the file's own
        // name would hide the folder and keep the ten thousand images in it.
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            if (rules.any { it.hits(segment) }) return false
        }
        return true
    }

    /**
     * Whether [path] is [folder] or sits inside it.
     *
     * Separator-aware on purpose. `startsWith` alone would put `/Downloads2` inside
     * `/Downloads`, so excluding one folder would silently take its neighbours with it - and
     * the neighbour is usually the folder somebody actually wanted, which makes it look like
     * the feature does not work rather than like a rule is too broad.
     */
    fun under(path: String, folder: String): Boolean {
        if (folder.isEmpty()) return false
        if (path == folder) return true
        return path.startsWith("$folder/")
    }

    /**
     * Read the list as somebody typed it.
     *
     * Commas or newlines, trimmed, blanks dropped, duplicates removed. Case is KEPT: these are
     * compared against real filenames, and on this filesystem `Log_` and `log_` are different
     * folders - folding them would quietly exclude more than was asked for, which is the
     * failure this whole file is written to avoid.
     */
    fun parse(raw: String): Set<String> =
        raw.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())

    /** Back to the one-per-line form the settings box shows. */
    fun format(lines: Set<String>): String = lines.joinToString("\n")
}
