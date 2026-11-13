package dev.niccc2007.filet.browser

/** What a thing standing in a folder's icon is drawn as. */
enum class PeekKind {
    /** Its own picture: a photo, a video frame, an album cover, an app's icon. */
    MEDIA,

    /** A small folder. */
    FOLDER,

    /** Its file-type icon. */
    FILE,
}

/**
 * Which of a folder's children stand in its icon.
 *
 * The icon says what kind of folder it is, so it shows what is there rather than the first few
 * names in the listing:
 *
 *  - **One kind inside** (only pictures, only files, only folders): up to [show] of that kind.
 *  - **A mix**: one of each kind there is, then filled to [show] by taking turns, media first.
 *  - **Within a kind, different types first**: a folder of PDFs, a sheet and a zip shows a PDF,
 *    the sheet and the zip, not three PDFs; photos and videos show one of each.
 *
 * The kinds are taken in a fixed order (media, folder, file), so the same folder always draws
 * the same picture, and the first thing returned is the one that stands in front.
 *
 * Pure: the caller says what each child is through [kindOf] and [typeOf], so this holds no file
 * system and is tested on plain values. The drawn pages are not decided here: they are what the
 * icon shows before this answers, and below the size where three things can be told apart.
 *
 * @param typeOf a key for "the same type", such as the extension, used only to vary the pick.
 */
fun <T> pickPeek(
    children: List<T>,
    kindOf: (T) -> PeekKind,
    typeOf: (T) -> String,
    show: Int = 3,
): List<T> {
    if (show <= 0 || children.isEmpty()) return emptyList()
    val byKind = PEEK_ORDER.associateWith { kind -> varied(children.filter { kindOf(it) == kind }, typeOf) }
    val present = PEEK_ORDER.filter { byKind.getValue(it).isNotEmpty() }
    if (present.size == 1) return byKind.getValue(present.single()).take(show)
    val out = ArrayList<T>(show)
    var round = 0
    while (out.size < show) {
        var added = false
        for (kind in present) {
            val next = byKind.getValue(kind).getOrNull(round) ?: continue
            if (out.size < show) { out += next; added = true }
        }
        if (!added) break
        round++
    }
    return out
}

/** The order kinds are taken in, and the order they stand in the folder. */
private val PEEK_ORDER = listOf(PeekKind.MEDIA, PeekKind.FOLDER, PeekKind.FILE)

/** The first of each type, in listing order, then the rest in listing order. */
private fun <T> varied(items: List<T>, typeOf: (T) -> String): List<T> {
    val seen = HashSet<String>()
    val first = ArrayList<T>()
    val rest = ArrayList<T>()
    for (item in items) if (seen.add(typeOf(item))) first += item else rest += item
    return first + rest
}
