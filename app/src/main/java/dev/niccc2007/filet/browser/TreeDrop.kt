package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * Where a drop on each row of a tree lands.
 *
 * Bug identified here: only folder rows took a drop. A file row, or the "Reading…" and
 * "Nothing in here" notes under an opened branch, took nothing, so the hover fell through to
 * the pane - and a pane drops into its working folder. With two branches open and the focus on
 * one of them, dragging into the OTHER branch's files offered to copy into the focused one.
 *
 * In a tree a row belongs to the branch it is drawn in, so that branch is where a drop on it
 * goes:
 * - a folder row takes the drop itself, as it does in a flat list, and so does an archive;
 * - a file row drops into the folder whose branch holds it, or the pane's own folder at the top
 *   level;
 * - a placeholder carries its branch's node, so it drops into that branch.
 *
 * Worked out from the rows' order rather than from a path's parent: the rows ARE the tree, and
 * a branch's children come back under the folder that was read, whatever their paths say.
 */
object TreeDrop {

    fun homes(rows: List<TreeRow>, cwd: VPath?): List<VPath?> {
        val out = ArrayList<VPath?>(rows.size)
        // The last folder row seen at each depth, which is the branch the next deeper row is in.
        val folders = ArrayList<VPath>()
        for (row in rows) {
            // An archive is a branch too, keyed by its mount. See TreeBranch.
            val branch = TreeBranch.keyOf(row.node)
            if (row.loading || row.empty) {
                out.add(branch ?: row.node.path)
                continue
            }
            out.add(branch ?: if (row.depth == 0) cwd else folders.getOrNull(row.depth - 1))
            if (branch != null) {
                while (folders.size > row.depth) folders.removeAt(folders.lastIndex)
                folders.add(branch)
            }
        }
        return out
    }
}
