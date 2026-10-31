package dev.niccc2007.filet.pick

/**
 * Whether a name typed into a save can be a file's name, and if not, why.
 *
 * A save is the one pick whose answer is made up rather than found, so it is the one that can be
 * wrong before anything is written. Checked as it is typed, so the card can say so beside the
 * field instead of the save failing afterwards with whatever the filesystem chose to say.
 */
object PickName {

    /** Bytes, not characters: the limit every filesystem Android writes to actually counts. */
    const val MAX_BYTES = 255

    /** Null when [name] is usable. Leading and trailing spaces are not part of it. */
    fun problem(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Type a name for the file."
            '/' in n || '\\' in n -> "A name cannot contain a slash."
            n.any { it.code < 0x20 } -> "A name cannot contain a control character."
            n == "." || n == ".." -> "That name is taken by the folder itself."
            n.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "That name is too long."
            else -> null
        }
    }

    /**
     * [name] ending in [extension], so the file is called what it is.
     *
     * A save makes one kind of file, and a name typed without its extension - or with another
     * picture's - would put JPEG bytes in a file called `.png`, which some apps then refuse to
     * open. A typed picture extension is replaced; anything else after a dot is part of the name
     * ("2026.10.30" stays) and the extension is added. Blank [extension]: left as typed.
     */
    fun fitted(name: String, extension: String): String {
        val n = name.trim()
        val ext = extension.lowercase().trimStart('.')
        if (ext.isEmpty() || n.isEmpty()) return n
        val dot = n.lastIndexOf('.')
        val typed = if (dot > 0) n.substring(dot + 1).lowercase() else ""
        return when {
            typed in sameKind(ext) -> n
            typed in PICTURES -> n.substring(0, dot) + "." + ext
            else -> "$n.$ext"
        }
    }

    /** The spellings one picture type goes by: a `.jpeg` is a JPEG as much as a `.jpg` is. */
    fun sameKind(extension: String): Set<String> = when (val e = extension.lowercase().trimStart('.')) {
        "jpg", "jpeg", "jpe", "jfif" -> setOf("jpg", "jpeg", "jpe", "jfif")
        "heic", "heif" -> setOf("heic", "heif")
        "tif", "tiff" -> setOf("tif", "tiff")
        else -> setOf(e)
    }

    private val PICTURES = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tif", "tiff")

    /** Whether [name] is already used in a folder holding [taken]. Case-blind, as shared storage is. */
    fun clashes(name: String, taken: Collection<String>): Boolean {
        val n = name.trim()
        return taken.any { it.equals(n, ignoreCase = true) }
    }
}
