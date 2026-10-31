package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath

/**
 * What a selection holds once folders and the things inside them can be picked at once.
 *
 * A tree shows a folder and its contents on one screen, so the two can both be picked - and a
 * selection holding a folder AND files inside it is a copy that writes those files twice and a
 * delete that fails half way, because the second delete finds them already gone with the folder.
 *
 * ## The parent absorbs
 *
 * Selecting a folder takes in whatever was already selected under it: those paths leave the
 * set, so the count is what an operation will actually act on, and the rows are drawn as
 * included rather than selected. Deselecting the folder therefore leaves nothing of them behind
 * - they were never separately held. A row already included cannot be picked on its own; the
 * answer to that tap is [toggle] returning null, which the caller reports.
 *
 * Pure, because "what will Delete act on" is the decision in this whole area that can be wrong
 * expensively.
 */
object TreeSelection {

    /** The selected folder above [path] that already takes it in, or null. */
    fun includedBy(selected: Set<VPath>, path: VPath): VPath? {
        if (selected.isEmpty()) return null
        var at = path.parent
        // Bounded: a parent chain is finite, but a bound costs nothing and a loop on the main
        // thread costs the app.
        var guard = 0
        while (at != null && guard++ < MAX_WALK) {
            if (at in selected) return at
            at = at.parent
        }
        return null
    }

    /** [selected] with every path a selected folder above it already takes in removed. */
    fun absorb(selected: Set<VPath>): Set<VPath> {
        if (selected.size < 2) return selected
        val kept = selected.filterTo(LinkedHashSet()) { includedBy(selected, it) == null }
        // The same instance when nothing changed, so a state holding it does not look changed.
        return if (kept.size == selected.size) selected else kept
    }

    /**
     * [path] tapped while selecting: added (absorbing what is under it) or removed. Null when a
     * selected folder above it already includes it, so the tap changes nothing.
     */
    fun toggle(selected: Set<VPath>, path: VPath): Set<VPath>? {
        if (includedBy(selected, path) != null) return null
        return if (path in selected) selected - path else absorb(selected + path)
    }

    /**
     * Invert over [rows], in their order. A row counts as picked when it is selected or
     * included - it is drawn ticked either way - so inverting a selected folder unpicks its
     * contents too rather than handing them back as a fresh selection.
     */
    fun invert(rows: List<VPath>, selected: Set<VPath>): Set<VPath> =
        absorb(rows.filterTo(LinkedHashSet()) { it !in selected && includedBy(selected, it) == null })

    /**
     * The nodes behind [selected], looked up in [sources] in order - the first that knows a path
     * wins. A path none of them knows is left out.
     */
    fun resolve(selected: Set<VPath>, vararg sources: Iterable<VNode>): List<VNode> {
        if (selected.isEmpty()) return emptyList()
        val found = HashMap<VPath, VNode>(selected.size * 2)
        for (source in sources) {
            for (node in source) {
                if (node.path in selected && node.path !in found) found[node.path] = node
            }
            if (found.size == selected.size) break
        }
        return selected.mapNotNull { found[it] }
    }

    private const val MAX_WALK = 64
}
