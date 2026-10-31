package dev.niccc2007.filet.pick

/**
 * Which pane the pick's card is drawn in.
 *
 * A one-line rule in a composable, pulled out here because it has already been wrong: the card
 * was pinned to the pane the pick STARTED in, so walking off to the Photos tab to find a picture
 * left the question behind - nothing on screen said a pick was running, nothing confirmed it and
 * nothing cancelled it either.
 *
 * Generic in the pane type, which is the whole reason this is testable. The decision is about
 * which pane and nothing else, so it needs no `PaneController`, no graph and no composition to
 * exercise, and the test reads it with strings. A pane compares as itself - `PaneController`
 * declares no `equals` - so `==` here is identity.
 */
object PickCard {

    /**
     * Whether the card belongs in [pane].
     *
     * Exactly one pane says yes, which is what keeps split view from drawing two cards for one
     * question.
     *
     * @param want what the pick is asking for. A file pick follows the eye and a folder pick
     *   stays with its folder, and the asymmetry is in the answers rather than in the card: a
     *   file pick's answer is a set of files, addable from any listing in the app, so the card
     *   belongs wherever the listing is; a folder pick's answer IS a pane's location, so the card
     *   has to be in the pane whose location it is about, or it would describe one folder while
     *   confirming another.
     * @param anchor the pick's own pane, or null once that tab has been closed. Closing it does
     *   not end the pick - only the card's X does - so a folder pick with no anchor left falls
     *   back to following the eye rather than becoming an operation with nothing on screen to
     *   end it.
     * @param focused the pane being looked at.
     * @param inApp true when Filet is picking for itself.
     * @param external true when another app is asking. Then there is one pane and it is this
     *   one, whatever the rest says.
     */
    fun <T> shownIn(
        want: PickSpec.Want,
        anchor: T?,
        focused: T?,
        pane: T,
        inApp: Boolean,
        external: Boolean,
    ): Boolean {
        // FIRST, and it is the whole reason this takes both flags.
        //
        // Bug identified here: the pane test used to be `the pick's pane === this pane`, which
        // answered false by itself when there was no pick - a null pane is no pane. Rewriting it
        // as "the focused pane" lost that for free, because the focused pane is always SOME
        // pane: with nothing being picked at all the card appeared anyway, in whichever pane was
        // in front, carrying the sentence a pick with no caller produces - "Choose a file / Tap
        // a file / Use this one". A chooser for nothing, that nothing could answer, covering the
        // bottom of the listing.
        //
        // The lesson is in the shape rather than the line: the old test conflated "which pane"
        // with "is anything running", and that was load-bearing by accident. Separating them
        // means the second has to be stated.
        if (!inApp && !external) return false
        if (external) return true
        if (want.isPlace && anchor != null) return anchor == pane
        return focused == pane
    }
}
