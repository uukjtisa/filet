package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * Files that just appeared sit at the bottom until the view settles.
 *
 * Windows Explorer does this and it is not laziness on its part. Sort order is a property of
 * the whole folder; "the thing I just made" is a property of the last five seconds. Honouring
 * the sort immediately means a new file is created, named `aaa.txt`, and instantly teleports
 * nine hundred rows up out of sight - so the user's next action, which is almost always to
 * rename or open the thing they just made, starts with hunting for it.
 *
 * So a copied, moved or created file is appended in arrival order and left there, and the sort
 * reasserts itself on the next refresh or the next time the folder is opened. The list is
 * therefore deliberately not in sort order for the life of that view, which is a thing worth
 * stating plainly rather than discovering.
 *
 * This is view state and never persisted. It has to die with the view: a tail restored from
 * disk would put "new" badges on files from last week.
 */
data class FreshTail(
    /** Paths to hold at the bottom, oldest arrival first. */
    val order: List<VPath> = emptyList(),
) {
    val isEmpty: Boolean get() = order.isEmpty()

    operator fun contains(path: VPath): Boolean = path in order

    /**
     * Note that [path] just arrived.
     *
     * An arrival that is already held keeps its original position rather than jumping to the
     * end. Copying over a file that is already in the tail is the same row arriving twice, and
     * moving it under the finger is the exact thing this whole mechanism exists to prevent.
     */
    fun arrived(path: VPath): FreshTail =
        if (path in order) this else FreshTail(order + path)

    /** Note that several arrived together, in the order given. */
    fun arrived(paths: Iterable<VPath>): FreshTail =
        paths.fold(this) { acc, p -> acc.arrived(p) }

    /** Forget one, because it was deleted or renamed away. */
    fun forget(path: VPath): FreshTail =
        if (path in order) FreshTail(order - path) else this

    /** The view settled - a refresh, a re-open, or leaving the folder. The sort takes over. */
    fun settled(): FreshTail = FreshTail()

    /**
     * Apply the tail to a listing that is already in its sort order.
     *
     * Held paths that are not in [sorted] are dropped rather than invented: a file can be
     * deleted by something else between arriving and being drawn, and a tail that outlives its
     * file would leave a row pointing at nothing.
     *
     * Returns [sorted] itself when nothing is held, so the common case allocates nothing.
     */
    fun applyTo(sorted: List<VNode>): List<VNode> {
        if (order.isEmpty()) return sorted
        val held = order.toHashSet()
        val tail = ArrayList<VNode>(order.size)
        val body = ArrayList<VNode>(sorted.size)
        for (node in sorted) {
            if (node.path in held) tail.add(node) else body.add(node)
        }
        if (tail.isEmpty()) return sorted
        // Arrival order, not listing order: the tail is a queue of things that happened, and
        // sorting it would reintroduce the jump for everything after the first arrival.
        val rank = order.withIndex().associate { (i, p) -> p to i }
        tail.sortBy { rank[it.path] ?: Int.MAX_VALUE }
        body.addAll(tail)
        return body
    }
}
