package dev.niccc2007.filet.browser

/**
 * One thing that can appear on the line under a file's name.
 *
 * ## Why an ordered list and not a set of switches
 *
 * The line used to be one boolean: on, and you got size and date in that order, for ever. The
 * fault was not that it was missing something - it was that it carried things that were not
 * wanted, and there was no way to say so short of turning the whole line off.
 *
 * A set of switches would not have fixed it either, because the order is part of the answer.
 * Switches have no order but the one the enum happens to declare, so "date then size" is not
 * expressible, and adding a part puts it wherever the declaration says rather than where it was
 * wanted. The bottom bar learned this already - see [BottomBarConfig], whose shape this follows
 * deliberately rather than inventing a second idiom for the same problem.
 *
 * ## What is here and what is not
 *
 * Only what a row can already answer from what it is holding: whether it is a folder, its size,
 * its modified time, its name, and - for a folder - how many things are in it. Nothing here
 * costs a query. A part that needed one would stall a fling, because this renders for every
 * visible row.
 */
enum class DetailPart(val id: String, val label: String, val note: String) {
    /**
     * Everything inside a folder, added up. Nothing on a file, whose size is already the
     * column on the right.
     */
    TOTAL(
        "total",
        "Folder total",
        "Combined size of everything in a folder.",
    ),
    SIZE("size", "File size", "The file's size, also shown on the right."),
    COUNT("count", "Item count", "Items in a folder, also shown on the right."),
    DATE("date", "Date", "Last modified, in the date format below."),
    KIND("kind", "Type", "File type, such as PDF or JPG."),
    ;

    /**
     * Whether this part has anything to say about an entry of this kind.
     *
     * The single exhaustive `when` that decides it, and the one thing both surfaces read: the
     * renderer gates on it so a part that does not apply prints empty rather than zero, and the
     * settings chooser gates on it so a side column is never offered a part that would render
     * nothing there. Two surfaces, one declaration - add a sixth part and this refuses to
     * compile until it has been decided, which is cheaper than remembering to update a list.
     */
    fun appliesTo(isDir: Boolean): Boolean = when (this) {
        TOTAL -> isDir
        SIZE -> !isDir
        COUNT -> isDir
        DATE -> true
        KIND -> !isDir
    }

    companion object {
        fun byId(id: String): DetailPart? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the line under a name is made of, and in what order.
 *
 * Mirrors [BottomBarConfig] on purpose: same `normalise`/`encode`/`toggled`/`moved` shape, so
 * the settings control is the same control and a reader who has understood one has understood
 * both. The one real difference is spelled out in [normalise], and it is the difference that
 * matters for this feature.
 */
object RowDetails {

    /**
     * What the line holds until somebody says otherwise.
     *
     * **The folder total and the date, and deliberately NOT the file size.** The old default
     * printed the size twice - once in the column on the right and again as the first thing
     * under the name:
     *
     * ```
     * Report.pdf            123 KB
     * 123 KB  ·  26 Oct 2026
     * ```
     *
     * Two lines to say one number. The column is the better place for it, because it lines up
     * down the list and can be compared at a glance, so the subtitle gives that space to
     * something the row was not saying at all:
     *
     * ```
     * Report.pdf            123 KB        New folder            13 items
     * 26 Oct 2026                         4.6 GB  ·  26 Oct 2026
     * ```
     *
     * [DetailPart.SIZE] and [DetailPart.COUNT] still exist and still work - somebody running
     * without the right-hand column needs them, and their notes say they repeat it. They are
     * simply not the default any more.
     */
    val DEFAULT = listOf(DetailPart.TOTAL, DetailPart.DATE)

    /** How the parts are joined. */
    const val JOIN = "  ·  "

    /**
     * Read a stored list, keeping what somebody chose.
     *
     * **An empty list is a real answer here, and that is the whole point of the feature.** This
     * is where it differs from [BottomBarConfig.normalise], which treats empty as "nothing was
     * stored" and hands back its defaults - correct there, because a bar with no buttons is not
     * a configuration, it is a screen with no way out. A line under a name with nothing on it
     * is exactly what somebody asking to get rid of the line means.
     *
     * So the two cases are kept apart: null is "never configured" and gets [DEFAULT]; an empty
     * string is "configured to show nothing" and gets nothing. Collapsing them would make the
     * line impossible to turn off, which is the defect this was built to remove.
     *
     * Unknown ids are dropped and duplicates collapse, so a stored value written by a later
     * version - or by hand - cannot put the same part on twice or crash the row.
     */
    fun normalise(stored: String?): List<DetailPart> {
        if (stored == null) return DEFAULT
        if (stored.isBlank()) return emptyList()
        return stored.split(',')
            .mapNotNull { DetailPart.byId(it.trim()) }
            .distinct()
    }

    /** Back to the stored form. An empty list encodes to an empty string, and must. */
    fun encode(parts: List<DetailPart>): String = parts.joinToString(",") { it.id }

    /** Add a part at the end, or take it off. */
    fun toggled(parts: List<DetailPart>, part: DetailPart): List<DetailPart> =
        if (part in parts) parts - part else parts + part

    /**
     * Move the part at [from] to [to].
     *
     * Out-of-range indices return the list untouched rather than throwing: the caller is a
     * composable whose list can have changed under it between the press and the handler.
     */
    fun moved(parts: List<DetailPart>, from: Int, to: Int): List<DetailPart> {
        if (from !in parts.indices || to !in parts.indices || from == to) return parts
        val list = parts.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** One part of the line, already rendered, with what it is so a caller can treat it apart. */
    data class Piece(val part: DetailPart, val text: String)

    /**
     * What stands in for a folder total nothing has measured yet.
     *
     * A dash rather than nothing, which is what it used to be. The figure arrives
     * asynchronously, so "not measured" is the normal state for the first moment a row is on
     * screen - and a part that silently vanishes and then appears makes the line jump by its own
     * width as the lookup lands, which reads as the list reflowing under the reader's eyes. A
     * dash holds the space and says the same thing: there is a number here and it is not in yet.
     *
     * An em dash, not "0 B" and not "unknown". Zero is a confident wrong answer on a row
     * somebody may be about to delete something from, and a word is wider than the figure it
     * stands in for.
     */
    const val DASH = "—"

    /**
     * What marks a figure that is a floor rather than a total.
     *
     * The index knows only what its crawl has reached, so its sum under a folder is "at least
     * this much" and never "this much". Printed bare it is simply wrong - 703 MB for a 39 GB
     * folder - and wrong in the direction nobody checks, because a plausible number invites no
     * second look. A trailing + costs one character and makes the figure true.
     *
     * The same word the details sheet already uses for the same situation; see
     * [FolderTally.partial].
     */
    const val FLOOR = "+"

    /**
     * The parts that have something to say about this entry, in order, already rendered.
     *
     * Parts that do not apply are dropped rather than carried as empty strings, so a folder with
     * size and kind on the list does not render two separators around nothing. That also means
     * the SAME configuration reads differently on a file and a folder, which is why the settings
     * preview shows one of each - a preview of only a file would make the folder behaviour a
     * surprise discovered later in a list.
     *
     * Returned piece by piece, rather than only as the joined string, because the row sometimes
     * has to treat ONE of them differently: a folder total that is still being confirmed pulses,
     * and the parts beside it must not. Pulsing the whole line to describe the uncertainty of
     * one part on it would say "this may change" about a date that is settled - a wrong claim,
     * and the kind this line is most able to do damage with.
     *
     * @param count how many entries a folder holds, or null when it has not been counted.
     *   Null prints nothing, never "0 items" - a confident wrong answer on a folder somebody is
     *   about to delete is the worst thing this line could do.
     * @param totalBytes everything under a folder, or null when nothing has measured it yet.
     *   Null renders as [DASH]; see there for why it is no longer nothing at all.
     */
    fun pieces(
        parts: List<DetailPart>,
        isDir: Boolean,
        size: Long,
        at: Long,
        count: Int?,
        name: String,
        display: EntryDisplay,
        total: FolderTotal = FolderTotal.Unknown,
        now: Long = System.currentTimeMillis(),
    ): List<Piece> = parts
        .map { part -> Piece(part, one(part, isDir, size, at, count, name, display, total, now)) }
        .filter { it.text.isNotEmpty() }

    /** The whole line as one string, which is what a row with nothing pending draws. */
    fun render(
        parts: List<DetailPart>,
        isDir: Boolean,
        size: Long,
        at: Long,
        count: Int?,
        name: String,
        display: EntryDisplay,
        total: FolderTotal = FolderTotal.Unknown,
        now: Long = System.currentTimeMillis(),
    ): String =
        pieces(parts, isDir, size, at, count, name, display, total, now)
            .joinToString(JOIN) { it.text }

    /**
     * One part, rendered for this entry, or empty when it does not apply.
     *
     * Public because the side column renders exactly one of these, and it must be the SAME one:
     * a column drawn by a second code path is a column that disagrees with the line under the
     * name about what "4.6 GB" means the moment either is touched.
     *
     * Exhaustive over [DetailPart] on purpose: a sixth part cannot be added without deciding
     * what it prints, which is the enforcement this repo reaches for before a comment.
     *
     * Whether the part applies at all is [DetailPart.appliesTo] rather than a condition repeated
     * in each branch - the branches had grown three different spellings of "not a folder", and
     * the settings chooser needed the same answer without rendering anything to get it.
     */
    fun one(
        part: DetailPart,
        isDir: Boolean,
        size: Long,
        at: Long,
        count: Int?,
        name: String,
        display: EntryDisplay,
        total: FolderTotal = FolderTotal.Unknown,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (!part.appliesTo(isDir)) return ""
        return when (part) {
            DetailPart.TOTAL -> totalOf(total)
            DetailPart.SIZE -> if (size < 0) "" else humanSize(size)
            DetailPart.COUNT -> countOf(count)
            DetailPart.DATE -> EntryFormat.date(at, display, now)
            DetailPart.KIND -> kindOf(isDir, name)
        }
    }

    /**
     * A folder total as the row prints it.
     *
     * Exhaustive over [FolderTotal], so a fifth state cannot be added without deciding whether
     * it is a floor - which is the decision that was skipped when the index's figure was printed
     * as though a crawl had seen the whole folder.
     */
    fun totalOf(total: FolderTotal): String = when (total) {
        FolderTotal.Unknown -> DASH
        is FolderTotal.AtLeast -> humanSize(total.bytes) + FLOOR
        is FolderTotal.Confirming -> humanSize(total.bytes) + FLOOR
        is FolderTotal.Settled -> humanSize(total.bytes)
    }

    /** Whether anything on this list needs a folder measured. */
    fun needsTotals(parts: List<DetailPart>): Boolean = DetailPart.TOTAL in parts

    /**
     * Whether this configuration needs a folder total ANYWHERE.
     *
     * Both surfaces, because the figure is looked up by the row and either surface can ask for
     * it. Checking only the line is how "Folder total" in the side column would have drawn a
     * permanent dash: the setting was reachable, the lookup was never started, and nothing
     * would have failed. Only the folder slot counts - [DetailPart.TOTAL] on a file renders
     * nothing, so a file-side total is not a reason to measure anything.
     */
    fun needsTotals(display: EntryDisplay): Boolean =
        needsTotals(display.parts) || display.sideFolder == DetailPart.TOTAL

    /**
     * Whether this configuration needs a folder's children counted.
     *
     * The same trap, and it was live: the count was read only when the old `measure` column was
     * on, so [DetailPart.COUNT] on the line under the name printed nothing whenever that column
     * was off - a part somebody had deliberately added, silently empty, because the thing that
     * fetched its value was gated on a different setting.
     */
    fun needsCount(display: EntryDisplay): Boolean =
        DetailPart.COUNT in display.parts || display.sideFolder == DetailPart.COUNT

    /** A folder's item count, or nothing when it has not been counted. */
    fun countOf(count: Int?): String = when {
        count == null -> ""
        count == 1 -> "1 item"
        else -> "$count items"
    }

    /**
     * The extension as a word.
     *
     * Upper-cased and capped, because this is a label rather than the name: an "extension" of
     * forty characters is a file with a dot in its name, and printing it would push the date off
     * the row. A name with no dot, a dotfile whose dot is the first character, and a folder all
     * have no kind to show.
     */
    fun kindOf(isDir: Boolean, name: String): String {
        if (isDir) return ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1)
        if (ext.length > MAX_KIND || ext.any { !it.isLetterOrDigit() }) return ""
        return ext.uppercase()
    }

    /** Longer than this is not an extension. See [kindOf]. */
    const val MAX_KIND = 8
}
