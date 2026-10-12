package dev.niccc2007.filet.playback

/**
 * How the full player draws itself.
 *
 * ## Why this is a choice and not a redesign
 *
 * The player was a flat background with the artwork in a rounded box, everything centred. It
 * worked and it looked like a preview pane, which is what it used to be. The replacement
 * takes its colour from the artwork, which is what every music app does and is the one trick
 * that can never be off-palette, because it IS the palette of the thing playing.
 *
 * Keeping four is a real cost - every face is a layout that has to survive the next change to
 * the transport, the queue and the video case. The recommendation was two, one plain and one
 * not; the decision was to keep the set. The cost is stated here so it is not rediscovered the
 * next time one of them silently stops matching the others.
 *
 * ## What stops them drifting
 *
 * Only the ARRANGEMENT differs. The top bar, the title block, the scrub bar, the transport,
 * the chip row and the queue sheet are one composable each, in `PlayerParts.kt`, and every
 * face composes those. A face is therefore a few dozen lines of layout and cannot quietly
 * grow a second copy of the transport - which is how four faces stops being four players.
 */
enum class PlayerFace(val id: String, val label: String, val about: String) {
    /** Flat, centred, no artwork bleed. What the player looked like before. */
    CLASSIC(
        "classic", "Classic",
        "Flat background, artwork in a box, everything centred.",
    ),

    /**
     * The artwork becomes the light in the room.
     *
     * Three layers rather than one blurred copy: a dark track blurred once is a dark
     * rectangle and produces no colour at all. See `AmbientBackdrop`.
     */
    AMBIENT(
        "ambient", "Ambient",
        "The artwork lights the screen behind itself. Big art, scrolling title.",
    ),

    /**
     * Type carries the screen; the art drops to under half the width.
     *
     * Buys a lot of vertical room, which is worth most on a short phone and on a video - a
     * 9:16 clip in a square box is mostly letterbox.
     */
    EDITORIAL(
        "editorial", "Editorial",
        "Small art beside the title. More on screen, less impact.",
    ),

    /**
     * The picture is the screen and the controls retract out of it.
     *
     * Two layers, not one: a blurred copy FILLS the screen and the sharp one is contained on
     * top of it, so nothing is stretched and a tall frame is not cropped to a band. Tapping
     * the picture hides the controls, which is the only reason this face exists.
     */
    IMMERSIVE(
        "immersive", "Immersive",
        "Full-bleed artwork. Small controls that tap away.",
    );

    /** Everything but Classic takes its colour from the artwork and lights the background. */
    val ambient: Boolean get() = this != CLASSIC

    companion object {
        val DEFAULT = AMBIENT

        fun of(stored: String?): PlayerFace = entries.firstOrNull { it.id == stored } ?: DEFAULT
    }
}
