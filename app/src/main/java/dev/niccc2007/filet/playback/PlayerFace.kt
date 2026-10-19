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
     * The artwork as a turning disc, ringed with light that bursts outward on the beat.
     *
     * The only face whose background is not the artwork: three soft lights in the sampled hue
     * drift behind it (see `HaloBackdrop`). The disc spins up, coasts and recovers like a
     * platter (see `Platter`), a glow sits on its rim, and the wave leaves that rim outward.
     *
     * Named for that ring of light. It was called Halo after the drifting gradient, which
     * stopped being the point of the face once the disc, the rim glow and the wave arrived.
     * The stored id was "aurora" and existing choices still resolve here - see [of].
     */
    HALO(
        "halo", "Halo",
        "The artwork as a turning disc, ringed with light that bursts with the beat.",
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
            AMBIENT, EDITORIAL, IMMERSIVE, HALO -> true
        }

    /** Whether the beat thins the veil over the artwork, so the picture itself punches. */
    val veilPulses: Boolean
        get() = when (this) {
            AMBIENT, EDITORIAL -> true
            CLASSIC, IMMERSIVE, HALO -> false
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
            CLASSIC, AMBIENT, EDITORIAL, HALO -> false
        }

    /** Whether this face draws the wave round a disc. Halo's, and only Halo's. */
    val hasWave: Boolean
        get() = when (this) {
            HALO -> true
            CLASSIC, AMBIENT, EDITORIAL, IMMERSIVE -> false
        }

    /** Whether there is a record to turn - which is what makes the drag controls mean anything. */
    val hasDisc: Boolean
        get() = when (this) {
            HALO -> true
            CLASSIC, AMBIENT, EDITORIAL, IMMERSIVE -> false
        }

    companion object {
        val DEFAULT = AMBIENT

        /**
         * The face for a stored id.
         *
         * An old id resolves to the face it became rather than falling through to the
         * default: the rename of Halo to Halo would otherwise have silently reset everybody
         * who had picked it. Aliased here rather than migrated in the preference store, so it
         * works whatever wrote the value - a restored backup or a settings import included.
         */
        fun of(stored: String?): PlayerFace {
            val id = LEGACY_IDS[stored] ?: stored
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }

        /** Ids this enum has used before, and what they are called now. */
        private val LEGACY_IDS = mapOf("aurora" to "halo")
    }
}

// ── the wave's fixed shape ────────────────────────────────────────────────────────────
//
// Five sliders for one visual effect read as a bloated panel, so two became fixed - wave at
// rest and wave on a beat - at the values their preferences defaulted to, so a player nobody
// had tuned looks exactly as before. Three stay settings: "Beat sensitivity" (`pulseSense`),
// "How much it reacts" (`pulseDepth`) and "Wave distance" (`waveGap`).
//
// Sensitivity was briefly fixed too, and that lost a real control. It is not a second depth:
// it scales the onset strength BEFORE the follower, so it decides which beats reach full
// strength (soft ones included, or only the hardest), while depth scales the swing AFTER it
// and decides how far the light moves on whatever fires.

/** The onset gain's default - what `Prefs.pulseSense` falls back to. */
const val PULSE_SENSE = 1.0f

/**
 * How deep the wave sits with nothing happening.
 *
 * 0.10, NOT `BeatRing`'s own parameter default of 0.12: the preference defaulted to 0.10 and
 * always passed it, so 0.10 is what anyone has ever actually seen.
 */
const val WAVE_REST = 0.10f

/** How far a full-strength beat takes the wave. The preference defaulted to 0.5. */
const val WAVE_SIZE = 0.5f
