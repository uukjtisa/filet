package dev.niccc2007.filet.browser

/**
 * What a copy made beside its original is called.
 *
 * ## Why this is not just a prefix
 *
 * `"Copy of " + name` is right until it meets any of three ordinary cases, and each one is the
 * kind of fault nobody reports because the result merely looks untidy:
 *
 * - **A second copy.** Prefixing again gives `Copy of Copy of report.pdf`, and a third gives
 *   the joke version. A counter belongs on the end of the stem instead.
 * - **A file with an extension.** The extension has to stay last or the file stops opening, so
 *   the counter goes before the dot rather than after the name.
 * - **A dotfile.** `.gitignore` is all extension and no stem by the usual rule, and inserting
 *   a counter into it produces something that is not the same kind of file.
 *
 * So the prefix is the easy part and the rest is this.
 */
object DuplicateName {

    const val PREFIX = "Copy of "

    /**
     * A name for a copy of [original] that nothing in [taken] already uses.
     *
     * @param taken the names already in the destination folder. Compared case-INSENSITIVELY,
     *   because the volumes Filet writes to are mostly case-insensitive and returning a name
     *   that differs only in case produces a collision the caller was asking to avoid.
     */
    fun of(original: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        val (stem, ext) = split(original)

        val first = PREFIX + stem + ext
        if (first.lowercase() !in lower) return first

        // From 2, because the un-numbered one IS the first copy.
        var n = 2
        while (true) {
            val candidate = "$PREFIX$stem ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
            // A folder with thousands of copies of one file is not a case worth looping
            // forever over; hand back something unique and let the caller get on with it.
            if (n > 9999) return "$PREFIX$stem (${System.currentTimeMillis()})$ext"
        }
    }

    /**
     * Stem and extension, where a leading dot belongs to the stem.
     *
     * `.gitignore` is a name, not an extension, and `archive.tar.gz` keeps only `.gz` - which
     * is what every file manager does and what keeps the counter next to the part a reader
     * thinks of as the name.
     */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }
}
