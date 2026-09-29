package dev.niccc2007.filet.browser

/**
 * Whether a folder pane is showing something that may already be wrong.
 *
 * ## What was actually wrong
 *
 * `navigateTo` re-lists on every open, and `restore` re-lists on back and forward, so opening
 * a folder was never the stale path. The hole was elsewhere: `reportAndRefresh` calls
 * `refreshPanes`, and `refreshPanes` re-lists **only the one or two panes on screen** - a
 * deliberate choice, with the comment "refreshing every tab would be a syscall storm", and it
 * is a fair one. The consequence nobody followed through on is that every OTHER tab keeps its
 * listing, and switching to a tab does not re-read it. So a file moved out of a folder is
 * still drawn in any tab that had that folder open, for as long as the app lives.
 *
 * ## Why a counter and not just "refresh on every tab switch"
 *
 * Re-listing on every switch is a directory read every time a thumb moves across the strip,
 * on a phone, for a folder that usually has not changed. Instead the world carries a revision
 * that every filesystem operation bumps, each pane remembers the revision it read at, and a
 * pane re-reads only when it is **visible** and **behind**. Nothing changed, nothing is read.
 *
 * This is the whole decision, isolated, because getting it wrong is either a stale screen
 * (the bug) or a syscall storm (the reason the bug existed).
 */
object FolderFreshness {

    /** A pane that has never listed anything. Deliberately not 0, which is a real revision. */
    const val NEVER = -1

    /**
     * @param isFolder only folder panes are decided here. Home, Settings, Scripts and the rest
     *   have their own answers in [refreshPlan], and re-listing them on this rule would re-run
     *   a network scan every time a tab was tapped.
     * @param hasPath a folder pane with no path has nothing to read.
     * @param loading a read is already in flight; a second one races it and the loser wins.
     * @param visible panes that are not on screen are left alone until they are, which is what
     *   keeps this from being the syscall storm it replaced.
     * @param listedAtRevision the world revision this pane last completed a listing at.
     * @param worldRevision the current revision, bumped by every operation that writes.
     * @param dragging something is being dragged right now, anywhere in the app.
     *
     * **A re-list under a drag cancels the drag.** Replacing the list replaces the row the
     * finger is on; its pointer handler is torn down with it and the gesture dies. The
     * reported symptom was dragging an item near the bottom of a pane and having it let go by
     * itself - which is the volume poll firing on its own timer, eight seconds after anything,
     * and landing mid-gesture.
     *
     * Deferring is safe and skipping would not be: the revision that made this pane stale is
     * still there when the drag ends, so the next `freshenIfStale` re-lists. The drop itself
     * bumps the revision again, so a drag that changes something re-lists immediately after.
     */
    fun shouldRelist(
        isFolder: Boolean,
        hasPath: Boolean,
        loading: Boolean,
        visible: Boolean,
        listedAtRevision: Int,
        worldRevision: Int,
        dragging: Boolean = false,
    ): Boolean {
        if (!isFolder || !hasPath) return false
        if (loading) return false
        if (!visible) return false
        if (dragging) return false
        return listedAtRevision != worldRevision
    }
}
