package dev.niccc2007.filet.playback

/**
 * What a tick box over a playlist means when several tracks are selected at once.
 *
 * ## Why this is not just a boolean
 *
 * "Is this track in that playlist" has a yes and a no. "Are these forty tracks in that
 * playlist" has a third answer - some of them - and that third answer is the common one, because
 * the reason somebody selects forty tracks and opens this dialogue is that they do not remember
 * which are already filed.
 *
 * So the box has three states and two of them answer the same tap. The rule, which is the whole
 * of the design decision:
 *
 * | State | A tap does |
 * |---|---|
 * | none of them are in it | put them all in |
 * | some of them are in it | put the rest in |
 * | all of them are in it | take them all out |
 *
 * Partial resolving UPWARDS is the part worth defending. The alternative - partial clearing to
 * empty - means a tap on a half-ticked box throws away filing somebody already did, and it is
 * the state they are in precisely because they could not remember doing it. Adding the
 * remainder is recoverable with one more tap; removing twelve tracks they wanted is not.
 *
 * Pure and tested because it is three branches that all look alike and the wrong one silently
 * edits data.
 */
object PlaylistMembership {

    /** How much of a selection a playlist already holds. */
    enum class State {
        NONE,
        SOME,
        ALL,
        ;

        /** For a tri-state box: null is the dash. */
        val ticked: Boolean? get() = when (this) {
            NONE -> false
            SOME -> null
            ALL -> true
        }
    }

    /** What a tap on the box does. */
    enum class Act { ADD, REMOVE }

    fun state(have: Int, total: Int): State = when {
        total <= 0 || have <= 0 -> State.NONE
        have >= total -> State.ALL
        else -> State.SOME
    }

    /** See the table in the class comment. */
    fun act(state: State): Act = if (state == State.ALL) Act.REMOVE else Act.ADD

    fun act(have: Int, total: Int): Act = act(state(have, total))

    /**
     * What the row says under the playlist's name.
     *
     * Here rather than in the composable so the wording is pinned by a test: these five
     * sentences are the only thing on screen that says what the tap is about to do, and a count
     * that disagrees with the box is worse than no count.
     */
    fun detail(have: Int, total: Int, listSize: Int): String = when (state(have, total)) {
        State.ALL ->
            if (total == 1) "In this playlist  ·  tap to take it out"
            else "All $total are in here  ·  tap to take them out"
        State.SOME -> "${total - have} to add  ·  $have already in here"
        State.NONE ->
            if (listSize == 0) "Empty" else dev.niccc2007.filet.browser.plural(listSize, "track", "tracks")
    }
}
