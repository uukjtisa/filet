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

/**
 * The selection while a finger slides across a gallery after a hold, from [start] to [current].
 *
 * Measured against [before] - the selection as it was when the hold began - and never against
 * the selection a frame ago. Sliding back towards the start therefore gives tiles back exactly
 * as they were: a run that only ever grew would keep every tile the finger had once crossed,
 * and the only way to undo an overshoot would be to tap each one off again.
 *
 * Starting on a tile that was already selected takes the run OUT of the selection instead, which
 * is how a selection is trimmed without lifting a finger. The start tile decides once, at the
 * press; a run that changed its mind halfway as it crossed selected and unselected tiles would
 * leave a pattern nobody drew.
 *
 * @param order the tiles in reading order, as drawn: a folded day's tiles are not in it.
 * @param adding whether the run joins the selection or leaves it. Given rather than read off
 *   [before] when the hold itself already changed [before]: a hold that extended a range up to
 *   its tile has selected that tile, and a slide from it still adds.
 * @return [before] unchanged when either end has gone from [order] - the list was re-read under
 *   the finger, and a range against a list that no longer matches the screen picks the wrong run.
 */
fun dragSelect(
    order: List<VPath>,
    before: Set<VPath>,
    start: VPath,
    current: VPath,
    adding: Boolean = start !in before,
): Set<VPath> {
    val from = order.indexOf(start)
    val to = order.indexOf(current)
    if (from < 0 || to < 0) return before
    val run = order.subList(minOf(from, to), maxOf(from, to) + 1)
    return if (adding) before + run else before - run.toSet()
}

/**
 * How far a drag-select scrolls the grid this frame, in pixels: negative up, positive down.
 *
 * Nothing until the finger is inside [zone] of an edge, then faster the deeper it goes, up to
 * [max] at the very edge and past it. Proportional rather than one fixed speed, because a fixed
 * speed is either too slow to cross a year of photos or too fast to stop on the right day - and
 * a finger held just inside the zone is asking to creep.
 *
 * @param y the finger, from the top of the grid's own viewport.
 */
fun edgeScrollStep(y: Float, height: Float, zone: Float, max: Float): Float {
    if (height <= 0f || zone <= 0f) return 0f
    // A grid shorter than two zones would scroll from anywhere in it; halve the zone instead.
    val z = minOf(zone, height / 2f)
    return when {
        y < z -> -max * ((z - y) / z).coerceAtMost(1f)
        y > height - z -> max * ((y - (height - z)) / z).coerceAtMost(1f)
        else -> 0f
    }
}
