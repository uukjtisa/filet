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
    ),

    /**
     * A drifting gradient in the track's colours, and the artwork as a turning disc.
     *
     * The only face whose background is not the artwork. Three soft lights taken from the
     * sampled hue move on their own slow ellipses at periods that do not divide into each
     * other, so the pattern never visibly repeats - see `AuroraBackdrop`. The disc turns
     * while the track plays and coasts to a stop when it is paused, which is the same thing
     * the other faces say by resizing their artwork, said the way a record player says it.
     */
    AURORA(
        "aurora", "Aurora",
        "A slow gradient in the track's colours, and the artwork as a turning disc.",
    );

    /** Everything but Classic takes its colour from the artwork and lights the background. */
    val ambient: Boolean get() = this != CLASSIC

    /**
     * Whether the beat may drive this face at all.
     *
     * ## Why this exists, and why it is a `when` rather than a flag
     *
     * The player's settings offered every beat control on every face. So Wave at rest, Wave on
     * a beat, Wave distance, Disc travel and Sound follows the disc were all adjustable while
     * looking at a face that has no wave and no disc - five controls that did nothing, on four
     * of the five looks, with no way to tell from the panel which was which. A setting that is
     * visible and inert is worse than a missing one: it reads as the feature being broken.
     *
     * An exhaustive `when` over the enum rather than a `val` in the constructor, because that
     * is the version the compiler enforces: a sixth face cannot be added without deciding
     * every one of these, which is exactly the mistake that shipped the Apps tab to a rail
     * that only exists above 720dp.
     */
    val pulses: Boolean
        get() = when (this) {
            // Classic is deliberately the plain one - a minimised player that happens to be
            // full screen. A beat in it would be the only thing about it that was not flat.
            CLASSIC -> false
            AMBIENT, EDITORIAL, IMMERSIVE, AURORA -> true
        }

    /** Whether the beat thins the veil over the artwork, so the picture itself punches. */
    val veilPulses: Boolean
        get() = when (this) {
            AMBIENT, EDITORIAL -> true
            CLASSIC, IMMERSIVE, AURORA -> false
        }

    /**
     * Whether the artwork itself breathes on the beat.
     *
     * Immersive only, and tiny - see `IMMERSIVE_BREATH`. At full-bleed size anything you can
     * measure is too much, but the eye catches a fraction of a percent, and on this face the
     * picture IS the screen so there is nothing else to move.
     */
    val artBreathes: Boolean
        get() = when (this) {
            IMMERSIVE -> true
            CLASSIC, AMBIENT, EDITORIAL, AURORA -> false
        }

    /** Whether this face draws the wave round a disc. Aurora's, and only Aurora's. */
    val hasWave: Boolean
        get() = when (this) {
            AURORA -> true
            CLASSIC, AMBIENT, EDITORIAL, IMMERSIVE -> false
        }

    /** Whether there is a record to turn - which is what makes the drag controls mean anything. */
    val hasDisc: Boolean
        get() = when (this) {
            AURORA -> true
            CLASSIC, AMBIENT, EDITORIAL, IMMERSIVE -> false
        }

    companion object {
        val DEFAULT = AMBIENT

        fun of(stored: String?): PlayerFace = entries.firstOrNull { it.id == stored } ?: DEFAULT
    }
}
