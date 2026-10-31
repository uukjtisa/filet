package dev.niccc2007.filet.playback

/**
 * Whether a player face stacks its parts or sets them side by side.
 *
 * ## The fault
 *
 * Every face was one vertical `Column`: artwork, then the title, then the scrubber, then the
 * transport, then the chips. Turned sideways, a phone has about 360dp of height to put all of
 * that in. The artwork is capped by whichever of width and height runs out first, so it
 * collapsed to a stamp, and the transport - the row with the Play button in it - was pushed off
 * the bottom entirely on the taller faces.
 *
 * ## The decision
 *
 * Side by side whenever the screen is clearly wider than it is tall - by shape, not by size.
 *
 * Bug identified here: this used to require the screen to be SHORT as well (under 560dp), on
 * the reasoning that a large tablet in landscape has room to stack. It has the room, and the
 * result was the portrait layout squashed vertically: a picture sized by the scarce axis with a
 * band of controls under it and the width wasted either side, which is the phone fault again at
 * a larger size. A landscape screen gets the landscape layout. The worry the height test
 * answered - a wide control column with half a screen of air in it - is answered where it
 * arises instead: [FaceLayout][dev.niccc2007.filet.handlers.FaceLayout] caps the controls at
 * [CONTROLS_MAX_DP] and centres the pair.
 *
 * [LANDSCAPE_RATIO] keeps a square-ish window stacked: a foldable's inner screen or a split
 * window near 1:1 has nothing to gain from two columns, and a Row there would be arbitrary.
 *
 * Pure and tested, because the alternative is reading the orientation inside five faces and
 * getting a different answer in one of them.
 */
object FaceShape {

    /** How much wider than tall a screen must be before the parts go side by side. */
    const val LANDSCAPE_RATIO = 1.2f

    /** The widest the controls column grows, so a tablet's spare width goes to the margins. */
    const val CONTROLS_MAX_DP = 560f

    /** Whether to put the artwork beside the controls rather than above them. */
    fun sideBySide(widthDp: Float, heightDp: Float): Boolean =
        heightDp > 0f && widthDp >= heightDp * LANDSCAPE_RATIO

    /**
     * How much of the width the artwork takes when the two are side by side.
     *
     * Slightly under half, because the controls column holds a title that wants to be read, a
     * scrubber that wants to be grabbed and a row of chips - and the artwork is square, so
     * giving it the larger share buys nothing it can use once the height has capped it anyway.
     */
    const val ART_SHARE = 0.46f
}
