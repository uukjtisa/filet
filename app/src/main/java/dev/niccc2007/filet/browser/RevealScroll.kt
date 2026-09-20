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
