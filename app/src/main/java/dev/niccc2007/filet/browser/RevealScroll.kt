package dev.niccc2007.filet.browser

import kotlin.math.max

/**
 * Where to scroll a listing so a particular file is actually in front of you.
 *
 * Bug identified: "go to containing folder" opened the folder and selected the row and stopped
 * there. The listing arrives at the top, so a file eight hundred rows down was selected and
 * off screen - present in the sense that it existed, and nowhere in the sense that matters.
 * Worse, the pane scrolls to the top whenever the folder changes, so even a scroll would have
 * been undone a moment later.
 *
 * Centred, not merely visible. A row pushed to the very bottom edge of the viewport satisfies
 * "scrolled into view" and still has to be hunted for, and a row at the very top reads as the
 * start of the folder rather than as the thing that was asked for.
 */
object RevealScroll {

    /** What to do with a reveal request against the listing as it stands. */
    enum class Act {
        /** Not here yet, and the listing is still arriving. Keep the request and look again. */
        WAIT,

        /** Scroll to it, and keep the request: later chunks may move it. */
        SCROLL_AGAIN,

        /** Scroll to it and let the request go. The listing is final. */
        SCROLL_DONE,

        /** Drop the request so a later folder does not jump. */
        GIVE_UP,
    }

    /**
     * Whether a reveal request can be acted on yet.
     *
     * Bug identified: this used to be two lines inside the list's effect, and it read a missing
     * target as "not in this listing". That was sound while a listing went from empty to complete
     * in one step. Once folders began arriving in chunks it became wrong twice over:
     *
     *  - **Absence is provisional.** A row that is not in the first 120 entries may be in the
     *    next 400. Giving up on chunk one threw the request away, and nothing scrolled.
     *  - **So is the index.** A position worked out from a partial list moves as the rest lands,
     *    because the whole set is re-sorted each time - so even a hit scrolled to a row that
     *    was about to be somewhere else.
     *
     * Both answers are therefore the same: wait for the read to finish. A folder small enough for
     * this to be noticeable finishes in milliseconds, and a folder large enough to matter is one
     * where scrolling to a position that is about to change is worse than scrolling once.
     *
     * @param settled whether the listing has finished arriving.
     * @param rowCount rows currently in the list.
     * @param targetIndex where the target sits, or -1 when it is not there.
     */
    fun act(
        settled: Boolean,
        rowCount: Int,
        targetIndex: Int,
        userTookOver: Boolean = false,
    ): Act = when {
        // Their scroll wins, always and immediately. A reveal is something the app was asked to
        // do a moment ago; a finger on the list is what is being asked for now.
        userTookOver -> Act.GIVE_UP

        // Found. Go there NOW rather than when the folder finishes reading.
        //
        // The first version of this fix waited for the whole listing, which was correct about
        // the index being provisional and wrong about what to do with that: the wait is visible,
        // and a person can tap during it. Scrolling immediately and again as later chunks land
        // costs nothing - the list is already on screen - and puts the row in front of them at
        // the first possible moment.
        targetIndex >= 0 && settled -> Act.SCROLL_DONE
        targetIndex >= 0 -> Act.SCROLL_AGAIN

        // Not found, and the listing is still arriving in chunks. Absence is provisional: a row
        // that is not in the first 120 entries may be in the next 400. Giving up here is what
        // stopped the reveal working at all once listings were chunked.
        !settled -> Act.WAIT

        // Not found and the read is over. Hidden by a filter, or deleted since the row was
        // drawn, or the folder is empty. Nothing to scroll to.
        else -> Act.GIVE_UP
    }

    /**
     * Which row to put at the top so [targetIndex] lands in the middle.
     *
     * Clamped at both ends, which is the whole subtlety. A file near the start cannot be
     * centred without scrolling above the first row, and a file near the end cannot be centred
     * without inventing rows past the last - so those cases settle for the nearest honest
     * position instead of leaving a gap.
     *
     * @param rowsOnScreen how many rows the viewport holds. Zero or one means there is nothing
     *   to centre within and the target itself goes to the top.
     * @param total rows in the listing.
     */
    fun firstVisibleFor(targetIndex: Int, rowsOnScreen: Int, total: Int): Int {
        if (targetIndex <= 0 || total <= 0) return 0
        if (rowsOnScreen <= 1) return targetIndex.coerceIn(0, max(0, total - 1))
        val above = (rowsOnScreen - 1) / 2
        val last = max(0, total - rowsOnScreen)
        return (targetIndex - above).coerceIn(0, last)
    }

    /**
     * How many rows fit in a viewport.
     *
     * Rounded down: a half-row at the bottom is not somewhere a file can be said to be.
     */
    fun rowsOnScreen(viewportPx: Int, rowPx: Int): Int {
        if (viewportPx <= 0 || rowPx <= 0) return 0
        return viewportPx / rowPx
    }

    /**
     * Whether a target already sits comfortably on screen.
     *
     * Used to leave the listing alone when it does. Scrolling a file that is already in front
     * of somebody is a jump they did not ask for, and it loses their place for nothing.
     *
     * @param edge rows at the top and bottom that count as "not comfortably" - a row half
     *   under the toolbar is visible and not useful.
     */
    fun alreadyShown(targetIndex: Int, firstVisible: Int, rowsOnScreen: Int, edge: Int = 1): Boolean {
        if (rowsOnScreen <= 0) return false
        val from = firstVisible + edge
        val to = firstVisible + rowsOnScreen - 1 - edge
        return targetIndex in from..to
    }
}
