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
     * How far past the end counts as asking, in density-independent pixels of FINGER travel.
     *
     * Was 72, with nothing drawn, and it fired on pulls nobody meant: 72dp of leftover scroll
     * is reached by the tail of an ordinary flick that lands on an end. Now it is a deliberate
     * pull the way Chrome's is - the indicator rides out at half the finger's speed
     * ([indicatorOffset]) and fills as it goes, so the finger sees how far is left and can back
     * off before it counts.
     */
    const val THRESHOLD_DP = 150f

    /** How far the indicator may travel in from the edge, in dp. */
    const val MAX_TRAVEL_DP = 96f

    /** How full the pull is, 0 to 1. The indicator's arc and opacity are drawn from this. */
    fun progress(distance: Float, threshold: Float): Float =
        if (threshold <= 0f) 1f else (distance / threshold).coerceIn(0f, 1f)

    /**
     * Where the indicator sits, in px from the edge, for a finger [distance] past the end.
     *
     * Half the finger's travel, eased into [maxTravel] so it slows as it nears the cap instead
     * of hitting a wall - the resistance that makes the pull feel like it is being stretched.
     * Never decreases with distance, and never passes the cap.
     */
    fun indicatorOffset(distance: Float, maxTravel: Float): Float {
        if (distance <= 0f || maxTravel <= 0f) return 0f
        return maxTravel * (1f - kotlin.math.exp(-0.5f * distance / maxTravel))
    }

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
