package dev.niccc2007.filet.browser

/**
 * Dragging something into a new position in a list.
 *
 * Identified against the tab strip: the tabs were in a fixed order and could not be
 * rearranged. The arithmetic is three lines and every one of them has an off-by-one in it,
 * which is why it lives here with a test rather than inside a pointer callback.
 */

/**
 * Move the item at [from] to [to], closing the gap behind it.
 *
 * An index outside the list is the identity rather than a crash: a drag can end after the tab
 * it was dragging has been closed by something else, and a reorder is never worth a crash.
 */
fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/**
 * Which slot a finger at [x] is over, given the resting centre of each item.
 *
 * The nearest centre, which is the same thing as "has it moved more than half a slot". An
 * item therefore swaps with its neighbour once the two have actually passed each other,
 * rather than the instant the finger crosses into the neighbour's box - the second of those
 * makes a tab dragged one pixel too far jitter between two positions on every frame.
 *
 * @param centres in the same order as the list, in whatever unit [x] is in.
 * @return an index inside the list, or the nearest end when the finger is past it.
 */
fun slotFor(x: Float, centres: List<Float>): Int {
    if (centres.isEmpty()) return 0
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (i in centres.indices) {
        val d = kotlin.math.abs(centres[i] - x)
        if (d < bestDistance) {
            bestDistance = d
            best = i
        }
    }
    return best
}

/**
 * Where a dragged item currently sits, given where it started and where the finger is.
 *
 * Kept separate from [movedItem] because the list is not rebuilt on every frame - the strip
 * draws the item under the finger and only commits the move on lift. Rebuilding per frame
 * looks identical and re-keys every row sixty times a second.
 */
fun dragTarget(from: Int, x: Float, centres: List<Float>): Int {
    if (from !in centres.indices) return from
    return slotFor(x, centres)
}
