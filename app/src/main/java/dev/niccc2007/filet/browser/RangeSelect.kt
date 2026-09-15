package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * Shift-click, as a phone gesture.
 *
 * A phone has no shift key, so the gesture has to come from somewhere else. It comes from a long
 * press: with nothing selected a long press opens the item's menu, and with a selection already
 * running it extends to where you pressed. One gesture, two meanings, and which one you get is
 * never ambiguous because the selection is on screen.
 *
 * This file is the arithmetic, with no Compose and no VFS in it, because the arithmetic is where
 * range selection goes wrong. Ranges are inclusive, they work in both directions, and the list
 * they index into is the SORTED, FILTERED list the user can actually see - extending a range
 * over rows that are hidden by a filter would select files nobody pointed at.
 */

/**
 * Where a range starts from.
 *
 * The anchor is the last row the user touched deliberately - the one they tapped or long-pressed
 * to begin the selection - not simply the lowest selected index. Using the lowest would make a
 * second range extend from the wrong end as soon as somebody selected upwards.
 */
data class SelectionAnchor(val path: VPath?)

/**
 * The selection after long-pressing [target] while [anchor] was the last deliberate pick.
 *
 * @param visible the rows as displayed, in display order, after sorting and filtering.
 * @param current what is selected now.
 * @return the new selection, or [current] unchanged when the gesture cannot mean a range.
 *
 * The range is ADDED to what is already selected rather than replacing it. Replacing is what a
 * bare shift-click does on a desktop, but on a phone the only way to build a selection is one
 * gesture at a time, and wiping four deliberate picks because the fifth was a range would be
 * losing work the user cannot cheaply redo.
 */
fun rangeSelect(
    visible: List<VPath>,
    current: Set<VPath>,
    anchor: VPath?,
    target: VPath,
): Set<VPath> {
    if (anchor == null) return current + target
    val from = visible.indexOf(anchor)
    val to = visible.indexOf(target)
    // Either end missing means the list changed under the gesture - a rename, a refresh, a
    // filter typed while the finger was down. Selecting the one row that is definitely there
    // beats guessing a range against a list that no longer matches what was on screen.
    if (from < 0 || to < 0) return current + target
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    return current + visible.subList(lo, hi + 1).toSet()
}

/**
 * What a long press means right now.
 *
 * Written as a decision rather than an `if` inside the composable because it is the one thing in
 * this design that can be wrong in a way nobody notices: a long press that silently does the
 * other thing reads as the app ignoring you.
 */
enum class LongPressAction {
    /** Nothing is selected: open this item's context menu. */
    MENU,

    /** A selection is running and this row is outside it: extend to here. */
    EXTEND,

    /** A selection is running and this row is part of it: the menu, acting on all of them. */
    MENU_FOR_SELECTION,
}

fun longPressAction(selectionSize: Int, targetIsSelected: Boolean): LongPressAction = when {
    selectionSize == 0 -> LongPressAction.MENU
    targetIsSelected -> LongPressAction.MENU_FOR_SELECTION
    else -> LongPressAction.EXTEND
}
