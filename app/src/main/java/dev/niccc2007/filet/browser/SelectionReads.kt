package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * Keeping a selection change from recomposing the whole pane.
 *
 * Bug identified here: the pane collected its whole `PaneState`, and `toggleSelect` replaces
 * that state, so every tap that selected a file recomposed the pane - header, listing and every
 * visible row - before the one row that changed could draw its highlight. On a large folder the
 * highlight landed visibly late. It was never slow to draw; it was queued behind work it did not
 * need.
 *
 * So the pane reads [forPane], where any non-empty selection collapses to one constant marker:
 * two states that differ only in WHICH files are selected compare equal, and the pane does not
 * hear about the change. Each row reads the real selection through its own derived boolean
 * instead, so only the rows whose answer flipped recompose.
 *
 * The marker keeps `selecting` true, because the dock, the tree's chevrons and the special
 * panes genuinely change when a selection starts or ends - that once-per-selection recomposition
 * is the right one. What the marker gives up is the CONTENTS: [PaneState.selected] read through
 * the pane's copy is never the real set. Anything that needs it reads `pane.state.value`, as the
 * long-press menu already does.
 */
object SelectionReads {

    /** Stands in for "something is selected". Matches no real path. */
    val SELECTING: Set<VPath> = setOf(VPath("selection-marker", "/"))

    fun forPane(s: PaneState): PaneState =
        if (s.selected.isEmpty()) s else s.copy(selected = SELECTING)
}
