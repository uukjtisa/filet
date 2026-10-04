package dev.niccc2007.filet.vfs.provider

/**
 * What a new archive is called, and the one decision that used to be made for you.
 *
 * ## The extension is yours, the encoding is the format's
 *
 * Compress used to take the typed name, strip whatever extension was on it, and append the
 * format's own. That was deliberate and it was protecting against a real case - picking Tar +
 * gzip with a pre-filled `Photos.zip` still in the box would otherwise make a gzipped tar
 * wearing a zip's name - but it did it by taking the choice away entirely. There was no way to
 * write a 7z called `addon.mcaddon`, and plenty of formats in the world are an ordinary
 * archive under a name of their own: `.mcaddon`, `.epub`, `.cbz`, `.jar`, `.apkm`, `.xapk`.
 *
 * So the suffix is a field now, pre-filled from the format and editable. The stale-extension
 * case is solved where it actually lives instead: [suffixWhenFormatChanges] moves the field
 * with the format until somebody edits it, after which the field is theirs and is left alone.
 *
 * The encoding never follows the name. A 7z called `.mcaddon` is still a 7z, which is the
 * whole point.
 */
object ArchiveNaming {

    /** Longer than this is not an extension, it is the rest of the filename. */
    const val MAX_SUFFIX = 16

    /**
     * Tidy a typed suffix: one leading dot, no separators, no spaces.
     *
     * An empty result is legitimate and means "no extension", which is a thing a person may
     * genuinely want and which the old code could not express.
     */
    fun cleanSuffix(typed: String): String {
        val body = typed.trim().trimStart('.').filterNot { it == '/' || it == '\\' || it.isWhitespace() }
        if (body.isEmpty()) return ""
        return "." + body.take(MAX_SUFFIX)
    }

    /** Tidy a typed base name. The separators are the only characters a name cannot hold. */
    fun cleanBase(typed: String): String =
        typed.trim().filterNot { it == '/' || it == '\\' }.ifEmpty { "Archive" }

    /**
     * What the suffix field should show after the format changes.
     *
     * Follows the format while the field still holds a format's own suffix - which is the
     * stale `.zip` case, solved by keeping the field correct rather than by overriding it at
     * the end. Once it holds something that is nobody's default, it is an answer somebody
     * typed and it survives switching formats.
     */
    fun suffixWhenFormatChanges(
        current: String,
        to: ArchiveFormat,
        known: List<ArchiveFormat> = Archives.creatable,
    ): String {
        val c = cleanSuffix(current)
        if (c.isEmpty()) return c
        val isSomeFormatsOwn = known.any { f -> f.extensions.any { ".$it".equals(c, ignoreCase = true) } }
        return if (isSomeFormatsOwn) to.suffix else c
    }

    /** Whether this suffix is one the chosen format would have picked for itself. */
    fun matchesFormat(suffix: String, format: ArchiveFormat): Boolean {
        val c = cleanSuffix(suffix)
        return format.extensions.any { ".$it".equals(c, ignoreCase = true) }
    }

    /**
     * A sentence for a suffix that does not match the format, or null when it does.
     *
     * Said rather than prevented. Writing a 7z called `.mcaddon` is the feature; writing one
     * called `.zip` by accident is the mistake this warns about, and the two are
     * indistinguishable from here - so the only honest move is to name what will happen and
     * let somebody decide.
     */
    fun mismatchNote(suffix: String, format: ArchiveFormat): String? {
        if (matchesFormat(suffix, format)) return null
        val c = cleanSuffix(suffix)
        if (c.isEmpty()) return "No extension. Filet goes by the contents; most other apps go by the name."
        return "Written as ${format.label}, named $c. Filet reads the contents either way, " +
            "and anything that goes by the name may not."
    }

    /**
     * The file name, avoiding one that is already there.
     *
     * Compress is one tap away from a selection, and a second tap quietly overwriting the
     * first archive is the way this feature loses data.
     */
    fun fileName(base: String, suffix: String, taken: (String) -> Boolean): String {
        val clean = cleanBase(base)
        val ext = cleanSuffix(suffix)
        var n = 1
        while (n < 10_000) {
            val candidate = if (n == 1) "$clean$ext" else "$clean ($n)$ext"
            if (!taken(candidate)) return candidate
            n++
        }
        return "$clean ${System.currentTimeMillis()}$ext"
    }
}
