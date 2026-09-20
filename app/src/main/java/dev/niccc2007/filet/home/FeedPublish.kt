package dev.niccc2007.filet.home

/**
 * When a partial feed result is allowed on screen.
 *
 * Bug identified: the home feed publishes from inside each folder's coroutine, so a pass emits
 * several times and each emission holds only the folders that happened to finish first. On a
 * cold start that is exactly right - rows appear as they are found instead of the screen
 * sitting empty. On a refresh it is the flicker in the report: the list already had the right
 * answer, and it was replaced by a shorter wrong one, then a longer wrong one, then the right
 * one again.
 *
 * Every intermediate state is a list that was never true. The rule is therefore about whether
 * there is anything to protect, not about how far through the pass it is.
 */
object FeedPublish {

    /**
     * @param screenIsEmpty whether the feed currently shows nothing.
     * @return whether a partial result should be drawn.
     *
     * Only when there is nothing yet. Once a full answer is on screen it stays until a new full
     * answer replaces it, so the reader never sees the list get shorter.
     */
    fun showPartial(screenIsEmpty: Boolean): Boolean = screenIsEmpty

    /**
     * Whether a publish may reach the screen at all.
     *
     * Bug identified, and reported a second time as the list flickering on refresh: the
     * partial guard was applied to the backing list and NOT to the list the screen renders.
     * Every folder coroutine wrote its intermediate answer straight to the visible rows, so a
     * refresh showed the old list, then a short wrong one, then a longer wrong one, then the
     * truth. The guard has to sit in front of everything a publish touches, not one of them.
     */
    fun publishable(complete: Boolean, screenIsEmpty: Boolean): Boolean =
        complete || showPartial(screenIsEmpty)
}
