package dev.niccc2007.filet.home

/**
 * How a category's files are drawn: the dated list, or a gallery of pictures.
 *
 * ## One declaration, read by every surface
 *
 * The switch in the header, the stored choice, the renderer and the thumbnail warm-up all read
 * this enum, and CategoryScreen picks the renderer with an exhaustive `when`. A third layout
 * cannot be added without deciding what every one of those does with it - the compiler asks.
 *
 * ## Per category, not one global flag
 *
 * Photos in a gallery and audio as a list is the normal wish, so each category keeps its own
 * choice. Only the categories with something to look at offer the switch: a gallery of
 * packages or documents would be a wall of identical glyphs. See [offeredFor].
 */
enum class CategoryLayout(val key: String, val label: String) {
    /** Newest first, in days, one row per file - the shape New files has. The default. */
    LIST("list", "List"),

    /** The same days, as tiles of the file's own picture: the photo, the video frame, the cover. */
    GALLERY("gallery", "Gallery"),
    ;

    companion object {
        /** Which categories offer the switch. The rest are always [LIST]. */
        fun offeredFor(category: MediaCategory): Boolean = when (category) {
            MediaCategory.IMAGES, MediaCategory.VIDEOS, MediaCategory.AUDIO -> true
            else -> false
        }

        /** The layout [category] is drawn in, given what is stored. Never a gallery it does not offer. */
        fun of(stored: Map<String, CategoryLayout>, category: MediaCategory): CategoryLayout =
            if (offeredFor(category)) stored[category.id] ?: LIST else LIST

        /**
         * `images=gallery,audio=list`. Unknown categories and unknown layouts are dropped rather
         * than refused, so a value written by a newer Filet reads as defaults, not a crash.
         */
        fun parse(raw: String?): Map<String, CategoryLayout> {
            if (raw.isNullOrBlank()) return emptyMap()
            val known = MediaCategory.entries.filter(::offeredFor).map { it.id }.toSet()
            val out = LinkedHashMap<String, CategoryLayout>()
            for (part in raw.split(',')) {
                val (id, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val layout = entries.firstOrNull { it.key == value.trim() } ?: continue
                if (id.trim() in known) out[id.trim()] = layout
            }
            return out
        }

        fun encode(map: Map<String, CategoryLayout>): String =
            map.entries.sortedBy { it.key }.joinToString(",") { it.key + "=" + it.value.key }
    }
}
