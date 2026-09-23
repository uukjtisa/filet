package dev.niccc2007.filet.browser

/**
 * Pulling past either end of a list asks for a refresh.
 *
 * Both ends, not just the top. The top is the conventional place for this and it is also the
 * least useful one in a file manager: the reason to refresh is usually that a long listing has
 * gone stale, and on a long listing the top is a scroll away from wherever you are. The bottom
 * is where you already are when you finish reading.
 *
 * The arithmetic is here, away from Compose, because all of it is a decision that can be
 * wrong: how far counts as a pull, what resets it, and whether letting go fires once or twice.
 * A gesture that fires twice re-lists a folder twice, and a gesture that never resets fires on
 * an ordinary flick.
 */
enum class PullEdge { TOP, BOTTOM }

/**
 * @param edge which end is being pulled, or null when nothing is.
 * @param distance how far past the end, always positive.
 * @param spent true once this pull has fired, so holding past the threshold cannot fire again
 *   without letting go first.
 */
data class PullState(
    val edge: PullEdge? = null,
    val distance: Float = 0f,
    val spent: Boolean = false,
) {
    /** Past the line, and not already used up - what the indicator draws itself from. */
    fun armed(threshold: Float): Boolean = !spent && edge != null && distance >= threshold
}

object EdgePull {

    /**
     * How far past the end counts as asking, in density-independent pixels.
     *
     * Roughly a thumb's travel. Short enough to be easy on purpose, long enough that the
     * rubber-banding at the end of a fast flick does not reach it.
     */
    const val THRESHOLD_DP = 72f

    /**
     * Fold one scroll delta into the pull.
     *
     * @param available the scroll the list did not consume. Positive is the finger travelling
     *   down the screen, which is a pull at the top; negative is a pull at the bottom.
     * @param atTop whether the list is scrolled fully to the start.
     * @param atBottom whether it is scrolled fully to the end.
     */
    fun onScroll(state: PullState, available: Float, atTop: Boolean, atBottom: Boolean): PullState {
        val edge = when {
            available > 0f && atTop -> PullEdge.TOP
            available < 0f && atBottom -> PullEdge.BOTTOM
            else -> null
        }
        // Not at an end, or pushing back towards the middle. Either way this is no longer a
        // pull, and holding the distance would let two unrelated nudges add up to a refresh.
        if (edge == null) return PullState()
        // Crossing from one end to the other without letting go starts again, rather than
        // carrying a distance earned at the other end of the list.
        if (state.edge != null && state.edge != edge) return PullState(edge, kotlin.math.abs(available))
        return state.copy(edge = edge, distance = state.distance + kotlin.math.abs(available))
    }

    /**
     * The finger came off.
     *
     * @return the state to keep, and whether this should refresh.
     */
    fun onRelease(state: PullState, threshold: Float): Pair<PullState, Boolean> =
        if (state.armed(threshold)) PullState() to true else PullState() to false
}
