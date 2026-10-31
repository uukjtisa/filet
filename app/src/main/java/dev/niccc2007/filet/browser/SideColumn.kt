package dev.niccc2007.filet.browser

/**
 * The column on the right of a row: one thing, or nothing.
 *
 * ## Why one slot rather than the two switches it replaces
 *
 * There were two independent booleans - `entry.measure` for the size/count column and
 * `entry.dateColumn` for a date beside it - and the pair could express a row with two columns on
 * the right, which is where the duplication came from in the first place: a 92dp date
 * column repeating the date already printed under the name, on a phone where the NAME was the
 * field paying for the width.
 *
 * Two switches also could not express the configurations that mattered. "Put the folder total
 * on the right instead of the item count" was unreachable, because the size column's content was
 * hard-coded rather than chosen. A slot that takes any [DetailPart] gets that for free, and the
 * same vocabulary now serves the line under the name and the column beside it - one enum, two
 * surfaces, so a part added later appears in both without a second list to remember.
 *
 * The narrowing is deliberate and it is a real narrowing: a row can no longer show a size column
 * AND a date column at once. On the width this app has to work at, that was never a layout worth
 * keeping - and the line under the name holds as many parts as anyone wants.
 *
 * ## Why files and folders are configured separately
 *
 * Because the useful answer differs, and the old code knew it: `measure = true` meant BYTES on a
 * file and an ITEM COUNT on a folder, a branch buried in the renderer where nobody could change
 * it. Making it two settings makes that branch the person's to decide - "sizes beside files,
 * totals beside folders" is a coherent and common wish, and it was not expressible at all.
 *
 * It also removes the dead-switch problem rather than hiding it. A file has no item count and a
 * folder has no size of its own, so a single shared slot would have offered options that render
 * nothing for half the list. See [DetailPart.appliesTo] and [optionsFor]: each side is offered
 * only the parts that can answer for it, derived from the same exhaustive `when` the renderer
 * uses, so the list of options cannot drift from the list of things that actually print.
 */
object SideColumn {

    /** Beside a file: its own size. What `measure = true` did for files. */
    val FILE_DEFAULT: DetailPart? = DetailPart.SIZE

    /** Beside a folder: how many things are in it. What `measure = true` did for folders. */
    val FOLDER_DEFAULT: DetailPart? = DetailPart.COUNT

    /**
     * Read a stored slot.
     *
     * The same three-way reading as [RowDetails.normalise], and for the same reason: null is
     * "never configured" and takes [fallback], while an empty string is "configured to show
     * nothing" and must survive as nothing. Collapse them and the column cannot be turned off,
     * which is the whole defect.
     *
     * An id this build does not know falls back rather than blanking. A part added by a later
     * version is a thing this build cannot draw, and the default is a better answer than an
     * empty column somebody never asked for.
     *
     * So does a part this side cannot answer - "item count" stored for the FILE side, by a
     * hand-edited preference file or a build that offered it. Kept as-is it would be an inert
     * setting twice over: an empty cell on every row, and a chooser with no option lit,
     * because [optionsFor] does not offer it. Falling back is the only reading that leaves
     * both surfaces saying something true.
     *
     * @param isDir which side this slot is, so the stored part can be checked against it.
     */
    fun normalise(stored: String?, fallback: DetailPart?, isDir: Boolean): DetailPart? = when {
        stored == null -> fallback
        stored.isBlank() -> null
        else -> DetailPart.byId(stored.trim())?.takeIf { it.appliesTo(isDir) } ?: fallback
    }

    /**
     * What a side was set to under the two booleans this replaced.
     *
     * The old pair was `entry.measure` - bytes beside a file and an item count beside a folder,
     * which is exactly each side's [fallback] - and `entry.dateColumn`, a date beside either:
     *
     * | Stored before | Becomes |
     * |---|---|
     * | measure on | the default for this side: file size, or item count |
     * | measure off, date column on | the date |
     * | both off | nothing |
     * | both on | the default, and the date is dropped |
     *
     * **Both on loses the date**, and that is the deliberate part. The pair could put two columns
     * on the right of a row, which is no longer expressible by design, and of the two it is the
     * date that has to go: a date column beside a line that already carries the date is the
     * duplication the row rework exists to remove, and the line can still hold it.
     *
     * Somebody who had turned a column OFF must not be handed one back because the setting grew
     * from switches into slots - a reset to defaults is indistinguishable from the feature being
     * broken for the one person who had configured it. That is the row of this table most worth
     * a test.
     */
    fun migrate(hadMeasure: Boolean, hadDateColumn: Boolean, fallback: DetailPart?): DetailPart? = when {
        hadMeasure -> fallback
        hadDateColumn -> DetailPart.DATE
        else -> null
    }

    /** Back to the stored form. Nothing encodes to an empty string, and must. */
    fun encode(part: DetailPart?): String = part?.id ?: ""

    /**
     * What this side may be set to, nothing first.
     *
     * Derived from [DetailPart.appliesTo] rather than written out, so the settings screen cannot
     * offer a control that does nothing - which this repo treats as the feature appearing broken
     * rather than as a harmless extra row.
     */
    fun optionsFor(isDir: Boolean): List<DetailPart?> =
        listOf<DetailPart?>(null) + DetailPart.entries.filter { it.appliesTo(isDir) }

    /** What the chooser calls the empty slot. */
    const val NOTHING = "None"

    fun labelOf(part: DetailPart?): String = part?.label ?: NOTHING

    /**
     * How wide the column is, by what is in it.
     *
     * A fixed width per part rather than wrap-content, because this column's whole value is
     * that it lines up down the list: digits that do not share a right edge cannot be compared
     * at a glance, which is the reason the size lives here rather than only on the line.
     *
     * A date is the widest by a long way - "27 September 2026" - and that is why it used to have
     * its own 92dp column and its own width gate. Those are kept, now as a property of what is
     * in the slot rather than of which slot it is.
     */
    fun widthDp(part: DetailPart?, isDir: Boolean): Int = when (part) {
        null -> 0
        DetailPart.DATE -> 92
        DetailPart.KIND -> 44
        DetailPart.COUNT -> 68
        DetailPart.TOTAL -> 68
        DetailPart.SIZE -> if (isDir) 68 else 62
    }

    /**
     * Whether a pane this wide has room for it.
     *
     * Width is a constraint and the setting is a choice, and both have to agree - collapsing
     * them into one flag is how a preference became unreachable on a phone once already. A date
     * needs the wider gate it always needed; everything else rides the size gate.
     */
    fun fits(part: DetailPart?, metrics: PaneMetrics): Boolean = when (part) {
        null -> false
        DetailPart.DATE -> metrics.showDate
        DetailPart.TOTAL, DetailPart.SIZE, DetailPart.COUNT, DetailPart.KIND -> metrics.showSize
    }

    /** Which slot a row of this kind reads. */
    fun slotFor(display: EntryDisplay, isDir: Boolean): DetailPart? =
        if (isDir) display.sideFolder else display.sideFile
}
