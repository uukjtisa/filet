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
     * @param screenIsEmpty whether the feed currently shows nothing.
     * @return whether a partial result should be drawn. Only when there is nothing yet.
     */
    fun showPartial(screenIsEmpty: Boolean): Boolean = screenIsEmpty

    /** Whether a publish of this kind may reach the screen. */
    fun publishable(kind: PublishKind, screenIsEmpty: Boolean): Boolean = when (kind) {
        PublishKind.COMPLETE -> true
        PublishKind.SPLICE -> true
        PublishKind.PARTIAL -> showPartial(screenIsEmpty)
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
