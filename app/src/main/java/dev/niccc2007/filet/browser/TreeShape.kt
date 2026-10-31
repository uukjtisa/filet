package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * Where a pane stands under the view it is drawn in.
 *
 * ## The rule
 *
 * A tree is always rooted at its drive. Anything that arrives - a shortcut, a bookmark, Recents,
 * a typed path, switching the list to a tree - opens the chain from the drive down to where it
 * went and focuses it. A list always shows the focused folder, so switching back opens the
 * folder that was focused rather than the drive the tree hangs from.
 *
 * Bug identified here: the tree was rooted wherever the pane happened to be, so a shortcut
 * produced a tree with no trunk, and a "Show where this is" was needed to put one back. With
 * the root fixed at the drive, every folder in a tree already shows where it is.
 *
 * Pure, so the same answer serves an arrival, a view switch and a history entry written under
 * the other view - three places that would otherwise each decide it for themselves.
 */
object TreeShape {

    /**
     * [s] as the current view draws it. Unchanged when it already fits, and for anything that
     * is not a folder or is under no drive (an archive mount, a remote with no volume).
     *
     * @param roots the drives, deepest match wins.
     */
    fun shape(s: PaneState, tree: Boolean, roots: List<VPath>): PaneState {
        if (s.kind != PaneKind.FOLDER) return s
        val cwd = s.cwd ?: return s
        if (tree) {
            val root = rootFor(cwd, roots) ?: return s
            if (root == cwd) return s
            val focus = s.active ?: cwd
            return s.copy(
                cwd = root,
                open = s.open + chainTo(root, cwd),
                active = focus,
                // Arrived at, so it is scrolled to rather than put back: a position remembered
                // for a flat folder means nothing in a tree of the whole drive.
                revealTarget = focus,
                revealIfHidden = false,
                resume = false,
            )
        }
        val focus = s.active ?: return s
        if (focus == cwd) return s.copy(active = null)
        return s.copy(cwd = focus, active = null, revealTarget = null, revealIfHidden = false)
    }

    /** The drive [path] is on, or null when it is on none of them. */
    fun rootFor(path: VPath, roots: List<VPath>): VPath? =
        roots.filter { TreeBranch.under(it, path) }.maxByOrNull { it.segments.size }

    /**
     * The folds that show [target] under [root]: every folder between them and [target] itself.
     * Never [root]: it is the tree, not a row in it, and a fold on it would be one more thing for
     * Back to close with nothing on screen changing.
     */
    fun chainTo(root: VPath, target: VPath): Set<VPath> = TreeRows.pathTo(root, target) - root
}
