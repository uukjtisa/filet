package dev.niccc2007.filet.browser

/**
 * The extension, set as a word, for a file whose type has no glyph of its own.
 *
 * ## Why only for those
 *
 * The icon set already draws the kinds worth drawing: an image, a video, an archive, an app
 * package, a document, code. Those glyphs say more at 15dp than three letters do, and replacing
 * them with text would be a downgrade. What it has nothing for is everything else -
 * [FileKind.OTHER] - which falls back to one generic sheet-of-paper glyph. A folder of `.mcaddon`,
 * `.iso`, `.bin` and `.sav` is four identical glyphs and four different things.
 *
 * So this fills exactly that gap: where the answer would have been the generic glyph, the
 * extension is drawn instead.
 *
 * ## Why a long extension gets nothing
 *
 * An extension long enough to need cutting cannot be shown honestly. `MCAD` is not a file type;
 * it is four characters of one, and a reader who has met `.mcaddon` will read it as something
 * else entirely. Shrinking the type instead only trades a wrong word for an unreadable one -
 * five characters in a 30dp tile is already 7sp.
 *
 * So past [MAX] there is no monogram and the generic glyph stands, which is honest about not
 * knowing rather than confident about the wrong thing.
 */
object Monogram {

    /** The longest token that still reads at tile size. */
    const val MAX = 5

    /**
     * The token to draw, or null when the kind glyph is the better answer.
     *
     * Rejects anything that is not purely letters and digits rather than stripping it down. A
     * `.c++` reduced to `C` claims to be a C file, and `.tar.bz2` is only ever asked about as
     * `bz2` anyway - so the conservative answer is to decline, not to invent a shorter type.
     */
    fun of(extension: String): String? {
        val t = extension.trim()
        if (t.isEmpty() || t.length > MAX) return null
        if (!t.all { it.isLetterOrDigit() }) return null
        // A purely numeric extension is a part number - `.001` from a split archive, `.7` from a
        // log rotation - and drawing "001" in a tile says nothing about what the file is.
        if (t.all { it.isDigit() }) return null
        return t.uppercase()
    }

    /**
     * Type size in sp for a token of this length, at a 30dp tile.
     *
     * Hand-picked per length rather than derived, because the steps are not linear: two
     * characters can be large enough to read across a room and five have to fit in the same box
     * with side bearings. Scaled by the caller for a bigger tile.
     */
    fun sizeSp(token: String): Float = when (token.length) {
        1 -> 13f
        2 -> 12f
        3 -> 9.5f
        4 -> 8f
        else -> 6.5f
    }
}
