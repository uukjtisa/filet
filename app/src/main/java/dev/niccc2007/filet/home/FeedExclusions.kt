package dev.niccc2007.filet.home

/**
 * What New files leaves out: whole folders, and anything whose name starts with a given prefix.
 *
 * ## Why a prefix and not a pattern
 *
 * The things that flood this list are named by machines, and machines name in prefixes:
 * `.thumbnails`, `.trashed-1699`, `log_2026-10-26`, `tmp_`. A glob or a regular expression
 * would cover all of that and several hundred ways to write a rule that silently matches
 * nothing - and a rule that matches nothing looks exactly like a rule that is working, because
 * the feed it is filtering is full either way.
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
     * The ones not in [DEFAULT_PREFIXES] stay offers: `log_` or `tmp_` can be a real file
     * somebody made, and a list that quietly hid those from the first launch would be
     * impossible to tell apart from a list that was not working.
     */
    val SUGGESTED = listOf(".", "log_", "tmp_", "cache_", "logs")

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
    val DEFAULT_PREFIXES: Set<String> = linkedSetOf(".")

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
     * @param prefixes name prefixes; a segment starting with one excludes that entry and,
     *   when the segment is a folder in the path, everything inside it.
     */
    fun allows(path: String, folders: Set<String>, prefixes: Set<String>): Boolean {
        val clean = path.replace('\\', '/').trimEnd('/')
        if (clean.isEmpty()) return true
        for (folder in folders) {
            if (under(clean, folder.replace('\\', '/').trimEnd('/'))) return false
        }
        if (prefixes.isEmpty()) return true
        // EVERY segment, not just the last.
        //
        // This is the subtree rule, and doing it per segment is what gets it for free: a file
        // deep inside `.thumbnails` has `.thumbnails` as one of its segments, so excluding the
        // folder and excluding its contents are the same test. Checking only the file's own
        // name would hide the folder and keep the ten thousand images in it.
        for (segment in clean.split('/')) {
            if (segment.isEmpty()) continue
            for (p in prefixes) {
                if (p.isNotEmpty() && segment.startsWith(p)) return false
            }
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
     * Read a prefix list as somebody typed it.
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
    fun format(prefixes: Set<String>): String = prefixes.joinToString("\n")
}
