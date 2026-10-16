package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * One row of a tree listing: a node, and how deep it sits.
 *
 * @param loading true for a folder that has been opened and whose children have not arrived.
 */
data class TreeRow(val node: VNode, val depth: Int, val loading: Boolean = false)

/**
 * The folder list, with opened folders showing their contents in place.
 *
 * ## What this is, and what it deliberately is not
 *
 * It is not a second browser. Asked for after using X-plore, and the useful finding from that
 * is that X-plore's tree is not a different screen - it is the same list with a different ROW
 * RENDERER. Same thumbnails, same path bar at the top, same everything; a folder row simply
 * opens underneath itself instead of replacing the screen. That is why it coexists with a
 * path bar at all, and it is why this can be a view mode rather than a redesign.
 *
 * So: the pane still has a current folder, the path bar still means what it meant, tapping a
 * folder's row still navigates into it, and every row is still a `FileRow` - which is what
 * keeps drag-and-drop, selection, thumbnails and the long-press menu working without any of
 * them knowing a tree exists.
 *
 * ## Why flattening is a pure function
 *
 * A tree in a `LazyColumn` has to be a flat list - that is what a lazy list is - so the whole
 * question is which rows, in what order, at what depth. Done inline inside a composable, that
 * arithmetic is untestable and wrong in ways that only show up with a folder open three deep
 * while another is loading. Here it is a fold over two maps and can be stated in a test.
 *
 * A branch that is open but not yet read emits a single [TreeRow] with `loading`, so the row
 * acknowledges the tap immediately. The alternative - nothing until the listing arrives - is
 * indistinguishable from the chevron not working on a slow card.
 */
object TreeRows {

    /**
     * @param roots the pane's own listing, already sorted and filtered.
     * @param open which folders are showing their contents.
     * @param branches children per opened folder, absent while it is still being read.
     * @param depth how deep the tree may go, as a guard rather than as a feature.
     */
    fun flatten(
        roots: List<VNode>,
        open: Set<VPath>,
        branches: Map<VPath, List<VNode>>,
        depth: Int = MAX_DEPTH,
    ): List<TreeRow> {
        val out = ArrayList<TreeRow>(roots.size + open.size * 8)
        fun walk(nodes: List<VNode>, at: Int) {
            for (node in nodes) {
                out.add(TreeRow(node, at))
                if (!node.isDir || node.path !in open) continue
                val kids = branches[node.path]
                when {
                    // Opened, nothing read back yet.
                    kids == null -> out.add(TreeRow(node, at + 1, loading = true))
                    // Opened and genuinely empty. Nothing is emitted: a row saying "empty"
                    // under every empty folder in a tree is noise, and the chevron already
                    // turned, which is the acknowledgement.
                    kids.isEmpty() -> Unit
                    at + 1 >= depth -> Unit
                    else -> walk(kids, at + 1)
                }
            }
        }
        walk(roots, 0)
        return out
    }

    /**
     * Opening or closing one folder.
     *
     * Closing drops everything under it as well, so a branch reopened later is read fresh
     * rather than redrawn from a listing that may be hours old - and so a thousand-row tree
     * does not stay in memory after it has been folded away.
     */
    fun toggle(open: Set<VPath>, path: VPath): Set<VPath> =
        if (path in open) open.filterNot { it == path || it.path.startsWith(path.path + "/") }.toSet()
        else open + path

    /**
     * How deep the tree will go.
     *
     * A guard, not a feature. Nothing stops somebody opening their way down a symlinked loop
     * or a forty-deep build directory, and a lazy list with a hundred thousand rows in it is
     * a frozen phone. Twelve is further than anyone navigates on purpose.
     */
    const val MAX_DEPTH = 12
}
