package dev.niccc2007.filet.browser

/**
 * Which tab stays active when the tab list changes.
 *
 * Bug identified: past some number of open tabs the strip started misbehaving and tapping
 * anywhere left the pane blank. It is not a limit, and it is not the strip. It is this, in
 * `closeTab`:
 *
 * ```
 * val a = it.activeA.coerceAtMost(lastIndex).let { v -> if (index < it.activeA) v - 1 else v }
 * ```
 *
 * The clamp runs FIRST and the decrement runs after it. With the active tab at the end of a
 * long strip, `coerceAtMost` has already taken one off for the tab that just went, and then
 * the decrement takes another - so the pointer lands two tabs early, or below zero, or
 * outside the list entirely. `paneFor` then returns null and the whole pane renders as
 * nothing, which is the blank pane in the report. It needs several tabs to show up, because
 * with two or three the clamp rarely bites.
 *
 * Two small functions with a test, rather than one clever expression with none.
 */

/**
 * Where an index ends up after the tab at [closed] is removed.
 *
 * @param newSize the size AFTER the removal. Zero is not a state this app allows, and is
 *   answered with 0 rather than -1 so a caller cannot index with the answer.
 *
 * Closing the active tab moves to the one that slid into its place, which is the next tab
 * along - or the new last one, if it was already the last.
 */
fun indexAfterClose(index: Int, closed: Int, newSize: Int): Int {
    if (newSize <= 0) return 0
    val moved = when {
        closed < index -> index - 1
        else -> index
    }
    return moved.coerceIn(0, newSize - 1)
}

/**
 * Where an index ends up after the tab at [from] is dragged to [to].
 *
 * The active tab is tracked by index, so a move has to carry the pointer with it or the
 * strip highlights whichever tab happens to land on that number - which reads as the drag
 * having selected something else.
 */
fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    from < to && index in (from + 1)..to -> index - 1
    to < from && index in to until from -> index + 1
    else -> index
}

/**
 * An index that is safe to open a pane with.
 *
 * The last line of defence. Every path into the tab list is supposed to keep the pointers
 * valid, and the one that did not made the app render a blank screen rather than fail - so
 * this clamps at the point of use as well. An empty list is the one case with no answer.
 */
fun safeTabIndex(index: Int, size: Int): Int? =
    if (size <= 0) null else index.coerceIn(0, size - 1)
