package dev.niccc2007.filet.browser

/**
 * Telling a tap on the split divider apart from a drag or a hold.
 *
 * The divider is a drag handle first, so the tap has to be the conservative reading: getting it
 * wrong the safe way loses a swap that can simply be tried again, and getting it wrong the other
 * way resizes the split every time somebody meant to swap. A tap-hold is explicitly not a tap:
 * a finger resting on the handle is the start of a drag, even when it never moves.
 *
 * Both thresholds are here with tests because both are judgement calls that only reveal
 * themselves on a real thumb, and because "it swaps when I try to drag it" and "it never swaps"
 * are the same code read two ways.
 */
object DividerTap {

    /**
     * Longer than this and it was a hold, not a tap.
     *
     * Android's own long-press is 500ms. This is shorter on purpose: the divider has no
     * long-press action, so the only thing a slow press can be is the start of a drag that
     * happened not to move yet.
     */
    const val MAX_DURATION_MS = 220L

    /**
     * Further than this and it was a drag.
     *
     * A few density-independent pixels, because a thumb never holds perfectly still - zero
     * tolerance means the tap essentially never fires on a touchscreen.
     */
    const val MAX_TRAVEL_DP = 8f

    /**
     * Was that a tap?
     *
     * @param durationMs how long the pointer was down.
     * @param travelDp the furthest it got from where it went down - not where it ended up. A
     *   drag out and back is a drag, and measuring the endpoint calls it a tap.
     */
    fun isTap(durationMs: Long, travelDp: Float): Boolean =
        durationMs in 0..MAX_DURATION_MS && travelDp <= MAX_TRAVEL_DP && !travelDp.isNaN()
}
