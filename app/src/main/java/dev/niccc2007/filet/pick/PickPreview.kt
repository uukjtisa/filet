package dev.niccc2007.filet.pick

/**
 * What a pick is about to do, shown in the folder it is about to do it to.
 *
 * ## Why a pick has a second step at all
 *
 * A folder pick used to end the moment the confirm was pressed: the tab closed, the browser
 * went back to where it started, and whatever had been chosen happened somewhere the reader
 * was no longer looking. Nothing on screen had ever said what WOULD appear, so "extract here"
 * was a button somebody pressed hopefully and then went to check.
 *
 * So the confirm is two presses now, and the first one answers the question. The destination's
 * own listing grows the entries that are about to be written, drawn as ghosts - present,
 * highlighted, obviously not real yet - and the summary sits under them rather than over them.
 * The second press commits.
 *
 * The preview is built by whoever asked for the pick, because only they know what their
 * operation produces: an extract knows its plan, a save knows its filename. The pick carries
 * it without understanding it.
 */
data class PickPreview(
    /** "3 folders and 128 files", said in the sheet. */
    val title: String,
    /** The detail line: sizes, collisions, anything that should stop the second press. */
    val detail: String,
    /** The rows to draw in the listing, in the order they would appear. */
    val entries: List<Ghost>,
    /** More than [entries] holds, so the sheet can say "and 412 more". */
    val extra: Int = 0,
    /** Non-null when the operation would be refused, which disables the commit. */
    val blocked: String? = null,
) {
    /** One row that does not exist yet. */
    data class Ghost(
        val name: String,
        val isDir: Boolean,
        /**
         * Why this row is special, or empty.
         *
         * "replaces what is there", "kept, renamed" - the things somebody would want to know
         * BEFORE pressing, which is the whole reason the preview exists.
         */
        val note: String = "",
        /** True for something the operation invents rather than copies, like a wrap folder. */
        val invented: Boolean = false,
    )

    companion object {
        /**
         * How many ghosts are worth drawing.
         *
         * Twelve. Enough to show the shape of what arrives - one wrap folder and its first
         * contents, or a handful of loose files - and few enough that the listing underneath is
         * still visible, which is the thing the preview is drawn INTO rather than over.
         */
        const val SHOWN = 12
    }
}
