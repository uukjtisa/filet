package dev.niccc2007.filet.browser

/**
 * Where a row being selected goes: this pane's selection, the live pick's list, or nowhere.
 * Pure, so the one-selection rule is a test.
 *
 * Decision: while a pick is live, the pick's list is the only selection. A file goes to it,
 * however it was selected; a folder goes nowhere, because no pick that lists files holds one,
 * and a pane selection started beside the pick is the second selection this exists to prevent.
 * A folder pick takes the open folder from its card, so it loses nothing by this.
 */
internal enum class PickRoute {
    /** No pick is live: the pane selects as it always does. */
    PANE,

    /** A file during a pick: into the pick's own list, as a tap on it puts it. */
    PICK,

    /** A folder during a pick: nothing is selected anywhere. */
    NOTHING,
    ;

    companion object {
        fun of(pickLive: Boolean, isDir: Boolean): PickRoute = when {
            !pickLive -> PANE
            isDir -> NOTHING
            else -> PICK
        }
    }
}
