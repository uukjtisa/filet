package dev.niccc2007.filet.home

/**
 * What kind of answer a publish is carrying.
 *
 * The distinction exists because one earlier fix could not make it and broke the fast path by
 * being right about something else.
 */
enum class PublishKind {
    /**
     * Some of the tracked folders have answered and the rest have not.
     *
     * Every intermediate state here is a list that was never true, so on a refresh it must not
     * reach the screen - that is the flicker.
     */
    PARTIAL,

    /** Every tracked folder answered. This is the truth and it always publishes. */
    COMPLETE,

    /**
     * One file the kernel has just named, merged into what is already shown.
     *
     * Not an intermediate subset of anything: it is a fact that was not known a moment ago.
     * It always publishes.
     */
    SPLICE,
}

/**
 * When a feed result is allowed on screen.
 *
 * Bug identified: the home feed publishes from inside each folder's coroutine, so a pass emits
 * several times and each emission holds only the folders that happened to finish first. On a
 * cold start that is exactly right - rows appear as they are found instead of the screen
 * sitting empty. On a refresh it was the flicker: the list already had the right answer, and it
 * was replaced by a shorter wrong one, then a longer wrong one, then the right one again.
 *
 * Bug identified in the fix for that, and reported as the feed showing a state from hours ago
 * after a cold start: the guard was applied to *everything* publish writes, which swept up the
 * watcher's fast path with it. A splice is not a partial answer - it is one file the kernel
 * has just named - so blocking it meant that once the feed had any content at all, a newly
 * arrived file could only appear if a whole pass happened to run to completion.
 *
 * Combined with passes cancelling each other on startup, that is the inconsistency in the
 * report: sometimes a complete pass survives and the feed is right, sometimes one does not and
 * a stale early answer stays on screen with every later fact thrown away.
 */
object FeedPublish {

    /**
     * How long a cold start may show nothing before a partial answer is better than an empty
     * card.
     *
     * Reported as stale rows appearing for about half a second and then being replaced. The
     * earlier pass at this blocked partials on a REFRESH, which was right, and left the cold
     * start open so the card would not sit blank - so the flash survived, shorter.
     *
     * A grace window is the honest shape for that trade. If the whole pass finishes inside it -
     * which is the ordinary case, and why the flash is brief rather than long - nothing partial
     * is ever drawn and the card renders once, correct. If storage is slow enough to exceed it,
     * a partial is genuinely better than an empty card and it appears.
     */
    const val PARTIAL_GRACE_MS = 450L

    /**
     * Whether a partial answer is already the final answer *for the rows it would show*.
     *
     * This is the part that makes the grace window rarely matter. A folder's own mtime is an
     * upper bound on when anything arrived in it, so if every folder still unread is older than
     * the oldest row about to be displayed, none of them can contribute a row inside the visible
     * window - and the subset on screen is, for those rows, the truth.
     *
     * Two conditions and both are needed:
     *
     *  - **The window has to be full.** With four rows gathered and room for eight, a folder
     *    holding anything at all lands inside the window whatever its mtime, because there is
     *    space below the oldest row.
     *  - **Every unread folder has to be older than the oldest shown row.** One that is newer
     *    might hold a file that belongs above it.
     *
     * The bound is sound for what this feed shows. A directory's mtime changes when an entry is
     * created, removed or renamed - which is what an arrival is. It does NOT change when an
     * existing file's contents are rewritten, and a feed of arrivals does not care about that.
     *
     * @param shownCount rows gathered so far.
     * @param shownLimit how many the card displays.
     * @param oldestShownAt the timestamp of the oldest row that would be displayed.
     * @param unreadFolderMtimes one mtime per tracked folder not yet read.
     */
    fun partialIsFinal(
        shownCount: Int,
        shownLimit: Int,
        oldestShownAt: Long,
        unreadFolderMtimes: List<Long>,
    ): Boolean =
        shownCount >= shownLimit && unreadFolderMtimes.none { it > oldestShownAt }

    /**
     * @param screenIsEmpty whether the feed currently shows nothing.
     * @param elapsedMs how long this pass has been running.
     * @param provenFinal see [partialIsFinal].
     * @return whether a partial result should be drawn.
     */
    fun showPartial(
        screenIsEmpty: Boolean,
        elapsedMs: Long = Long.MAX_VALUE,
        provenFinal: Boolean = false,
    ): Boolean {
        // Never over content. A refresh keeps the answer it already has until a better one is
        // ready; this is the half the earlier fix got right and it is unchanged.
        if (!screenIsEmpty) return false
        return provenFinal || elapsedMs >= PARTIAL_GRACE_MS
    }

    /** Whether a publish of this kind may reach the screen. */
    fun publishable(
        kind: PublishKind,
        screenIsEmpty: Boolean,
        elapsedMs: Long = Long.MAX_VALUE,
        provenFinal: Boolean = false,
    ): Boolean = when (kind) {
        PublishKind.COMPLETE -> true
        PublishKind.SPLICE -> true
        PublishKind.PARTIAL -> showPartial(screenIsEmpty, elapsedMs, provenFinal)
    }

    /**
     * Whether a refresh asked for while one is already running should kill it.
     *
     * It should not, and this is the second half of the stale-feed report. `onFirstScreen`
     * refreshes, then the tracked-folder flow emits its current value and refreshes again, and
     * then the Home screen composes and refreshes a third time. Each one cancelled the last,
     * and the only publish that sets the settled list is the one at the very end of a pass -
     * so a startup could easily finish with no complete publish at all, leaving whichever
     * partial happened to land first on screen for good.
     *
     * A pass in flight is allowed to finish and one more is queued behind it. Queued, not
     * counted: ten requests during one pass are still one more pass, because they would all
     * read the same folders and produce the same answer.
     */
    fun cancelsRunningPass(): Boolean = false
}
