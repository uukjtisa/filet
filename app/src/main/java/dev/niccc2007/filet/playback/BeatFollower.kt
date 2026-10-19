package dev.niccc2007.filet.playback

import kotlin.math.exp
import kotlin.math.max

/**
 * Turning a stream of onset strengths into the numbers a face can draw, with no Compose in it.
 *
 * ## Why this is a class and not a dozen lines inside the loop
 *
 * It was a dozen lines inside the loop, twice, and it was wrong both times:
 *
 * 1. **The first version compared the envelope against a hardcoded resting point of 0.34.**
 *    The envelope decays to zero between hits, so every moment that was not a drum measured a
 *    third BELOW rest and the lights were multiplied down. Switching the pulse on made the glow
 *    dimmer on average than switching it off.
 * 2. **The second compared it against its own three-second average.** That fixed the sign, and
 *    introduced a subtler failure: a three-second window tracks the beat pattern itself, so the
 *    average climbed into the rhythm it was supposed to be measured against and the swing
 *    shrank to almost nothing over the first few seconds of a song. Reported as the pulse
 *    disappearing three or four seconds in - which is the averaging window, exactly.
 *
 * Neither is visible by looking at a light. Both are obvious in a test that feeds a synthetic
 * track through and asserts the swing is still there at the end of it. So the arithmetic lives
 * here, where it can be driven at a thousand frames a second with no phone attached.
 *
 * ## What it actually does
 *
 * Automatic gain control, then DC removal, which is the order that matters:
 *
 * - **An envelope** with an instant attack and an exponential tail, so a twenty-millisecond hit
 *   becomes something an eye can catch ([DECAY_MS]).
 * - **A floor and a ceiling**, each an asymmetric follower, which between them say what this
 *   track's quiet and its loudest actually are.
 * - **Normalise** against those, so the signal uses all of 0 to 1 whatever the music is doing.
 *   This is the step whose absence caused failure 2.
 * - **Remove the long-run average** ([MEAN_MS]), which is what keeps the glow's brightness
 *   honest - the swing averages about zero, so a pulse can only move the light around where it
 *   already was. This is the step whose absence caused failure 1.
 *
 * Everything else - the wave's asymmetric drive, the rare travelling impulse, the smoothed
 * loudness - hangs off those four.
 */
class BeatFollower {

    /** The punch, 0 to 1, with its tail. */
    var level = 0f
        private set

    /** The punch measured against what this track has been doing, signed. See [beatSwing]. */
    var swing = 0f
        private set

    /** The wave's amplitude: fast to rise, slow to fall, zero through anything that is not a rise. */
    var blast = 0f
        private set

    /** 1 the moment a hit lands well above this track's normal, 0 by [SHOCK_MS] later. */
    var shock = 0f
        private set

    /** How loud it is now, smoothed - a shape rather than a hit. */
    var loud = 0f
        private set

    /** Where this track sits between hits, and how hard it hits. Exposed for the diagnostics. */
    var floor = 0f
        private set
    var ceiling = 0f
        private set
    var mean = 0f
        private set

    private var wasOver = false

    /**
     * Advance by [gapMs] milliseconds, having read [raw] from the map.
     *
     * @param raw the onset strength at the position being heard, already scaled by the
     *   sensitivity and clamped to 0..1.
     * @param loudNow the loudness envelope at the same position, 0 to 1.
     */
    fun step(raw: Float, loudNow: Float, gapMs: Float) {
        val gap = gapMs.coerceIn(0f, 250f)

        // ── the punch: instant up, exponential down ──
        level = max(level * exp(-gap / DECAY_MS), raw)

        // ── what this track's quiet and its loudest are ──
        //
        // Asymmetric on purpose, in opposite directions. The floor has to drop promptly into a
        // quiet passage and must NOT be dragged up by the hits - a symmetrical follower would
        // settle at the average, which is what the mean below already is. The ceiling is the
        // mirror of that: quick to acknowledge a hard hit, slow to forget one.
        floor += (level - floor) *
            (gap / (if (level < floor) FLOOR_FALL_MS else FLOOR_RISE_MS)).coerceIn(0f, 1f)
        ceiling += (level - ceiling) *
            (gap / (if (level > ceiling) CEIL_RISE_MS else CEIL_FALL_MS)).coerceIn(0f, 1f)

        // ── normalise, THEN remove the average ──
        //
        // This order is the whole fix. Normalising restores the full range whatever the music
        // is doing, so the average can be removed over a long window without flattening the
        // movement - and a long window is what stops the average chasing the beat.
        val span = (ceiling - floor).coerceAtLeast(MIN_SPAN)
        val norm = ((level - floor) / span).coerceIn(0f, 1f)
        mean += (norm - mean) * (gap / MEAN_MS).coerceIn(0f, 1f)
        swing = beatSwing(norm, mean)

        // ── the wave: instant up, fast exponential down, flat when nothing is happening ──
        //
        // A burst, not a swell. See [WAVE_RELEASE_MS] for the linear chase this replaced and why
        // it could never be sharp.
        val want = (max(0f, swing) * WAVE_GAIN).coerceIn(0f, 1f)
        blast = max(want, blast * exp(-gap / WAVE_RELEASE_MS))

        // ── the impulse: only the big ones, and only once each ──
        val over = swing > SHOCK_AT
        shock = if (over && !wasOver) 1f else max(0f, shock - gap / SHOCK_MS)
        wasOver = over

        // ── the figure: loudness, smoothed ──
        loud += (loudNow - loud) * (gap / LOUD_MS).coerceIn(0f, 1f)
    }

    /** Nothing is playing. Everything goes to rest, including the followers. */
    fun reset() {
        level = 0f
        swing = 0f
        blast = 0f
        shock = 0f
        loud = 0f
        floor = 0f
        ceiling = 0f
        mean = 0f
        wasOver = false
    }

    /** One line for the log, so a report about this can carry numbers rather than adjectives. */
    fun describe(): String =
        "lvl=%.2f floor=%.2f ceil=%.2f mean=%.2f swing=%+.2f blast=%.2f".format(
            level, floor, ceiling, mean, swing, blast,
        )
}
