package dev.niccc2007.filet.playback

/**
 * What happens to the pitch when the speed changes.
 *
 * ## The fault this exists for
 *
 * Slowed playback was unintelligible below about 0.7x, and the reason is the framework's,
 * not Filet's: at pitch 1.0 Android holds the pitch with a
 * TIME-STRETCHER, which cuts the audio into grains and overlaps them. Above about 0.85x that
 * is transparent. Below it the grains start to audibly repeat - a metallic flutter on music
 * and a stutter on speech - and by 0.6x a voice is mush.
 *
 * **Filet cannot replace that stretcher.** It is inside `AudioTrack`, the knob that selects a
 * better one (`AUDIO_STRETCH_MODE_VOICE`) is not public API, and doing it properly would mean
 * decoding the audio ourselves and running our own time-stretch - a real DSP job, not a
 * setting. What Filet can do is stop walking into it.
 *
 * ## So there are three answers, and the default is the one that is never unintelligible
 *
 * [AUTO] holds the pitch while the stretcher is good at it and lets the pitch fall below
 * that, so a slowed voice is deep rather than garbled. It is the default because an
 * intelligible wrong-pitched voice beats an unintelligible right-pitched one every time, and
 * because the crossover is exactly where the artefacts start rather than a number somebody
 * liked.
 *
 * The other two exist because either can be the right answer: a musician slowing a solo wants
 * the pitch held whatever it costs, and somebody who likes the tape effect wants it always.
 */
enum class PitchMode(val id: String, val label: String, val about: String) {

    /**
     * Hold the pitch while that still sounds good; let it fall when it does not.
     *
     * The crossover is [HOLD_FLOOR]. Above it the stretcher is transparent and the pitch is
     * worth keeping; below it the stretcher is the thing you can hear, and resampling - which
     * has no artefacts at all, because there is nothing to invent - is the better trade.
     */
    AUTO(
        "auto", "Automatic",
        "Keeps the pitch while that sounds clean, and lets it drop when holding it would " +
            "start to warble. Never unintelligible.",
    ),

    /** Always stretch. Right pitch at any speed, and audibly granular when slowed a long way. */
    HOLD(
        "hold", "Keep the pitch",
        "Always holds the original pitch. Below 0.75× Android's time-stretcher warbles - " +
            "that is the stretcher, not Filet dropping anything.",
    ),

    /** Always resample. Smooth at any speed, and a slowed voice goes deep like a tape. */
    FOLLOW(
        "follow", "Let it drop",
        "Pitch follows the speed, like a tape. Perfectly smooth at every speed, and a slowed " +
            "voice goes deep.",
    );

    /**
     * The pitch to ask the decoder for at [speed].
     *
     * Pure, and the one piece of this that is worth a test: it is wrong in a way nobody
     * notices until they slow something down in a quiet room.
     */
    fun pitchFor(speed: Float): Float = when (this) {
        HOLD -> 1f
        FOLLOW -> speed
        AUTO -> if (speed >= HOLD_FLOOR) {
            // Nothing to trade above the floor: the stretcher is transparent there, so the
            // audio is not resampled at all.
            1f
        } else {
            // Below it, the two costs are SHARED instead of one of them being paid in full.
            //
            // The first version let the pitch follow outright below the floor, which traded
            // one unintelligible thing for another: at 0.5x that is an octave down, and a
            // voice an octave down is no easier to follow than a warbling one.
            //
            // What the framework actually does with `PlaybackParams(speed = S, pitch = P)`
            // is resample by P and time-stretch by S/P. P = 1 puts the whole of the slowdown
            // through the stretcher (clean pitch, warble); P = S puts the whole of it
            // through the resampler (smooth, octave down). Anything between splits it.
            //
            // `P = (S / floor) ^ SHARE` is that split, as a curve rather than a crossover:
            // it is exactly 1 at the floor, so there is no step to hear, and at 0.6x it is
            // about 0.89 - a drop of two semitones, with the stretcher left doing 0.68,
            // which it manages without falling apart. Both costs stay small where it matters
            // instead of one of them being zero and the other unbearable.
            val share = (speed / HOLD_FLOOR).coerceIn(0.001f, 1f)
            Math.pow(share.toDouble(), SHARE.toDouble()).toFloat().coerceIn(PITCH_FLOOR, 1f)
        }
    }

    companion object {
        /**
         * Where the stretcher stops being transparent.
         *
         * 0.85 by ear on the reported case and by the usual account of how sonic behaves:
         * at 0.9 it is clean, at 0.8 there is a flutter on sustained notes, at 0.6 a voice
         * is mush. Above 1.0 the stretcher is fine at any speed anybody uses, which is why
         * this is a floor and not a band.
         */
        const val HOLD_FLOOR = 0.85f

        /**
         * How much of a slowdown the RESAMPLER takes, as an exponent.
         *
         * 0 would be the old hold-everything (all warble, no pitch change); 1 would be
         * pitch-follows (no warble, full octave drop). 0.35 puts about a third of it through
         * the resampler, which at 0.6x is two semitones of pitch and a stretch of 0.68 -
         * both inside what a voice survives. Lower it and the warble comes back; raise it
         * and the voice starts sinking again.
         */
        const val SHARE = 0.35f

        /**
         * The deepest Automatic will ever go: about five semitones.
         *
         * At the very bottom of the rail the curve alone would still drop a long way, and
         * past this point a voice stops being a voice whatever the stretcher is doing. Below
         * here Automatic accepts the warble rather than keep sinking - that is the choice
         * that leaves words in it.
         */
        const val PITCH_FLOOR = 0.75f

        val DEFAULT = AUTO

        fun of(stored: String?): PitchMode = entries.firstOrNull { it.id == stored } ?: DEFAULT
    }
}

/**
 * The speed range the slider offers, and how hard the player backs off when refused.
 *
 * Top level rather than on [dev.niccc2007.filet.playback.Playback] because its companion is
 * private and both the player and the sheet that drives it need these - and two copies of a
 * range is how a slider ends up with a position the decoder will not take.
 *
 * Wider than the 0.5-2.0 every podcast player settles on, because it was asked for - and
 * nowhere near the 0.01x to 100x that was asked for, because those are not refusals Filet is
 * making. Android resamples through `AudioTrack`, and a rate far outside a quarter to four
 * times throws rather than playing: at 100x a 48 kHz track is 4.8 MHz of samples a second
 * into a sink that runs at 48. The player starts here and walks its ceiling DOWN on the
 * first refusal, so the rail ends where the phone actually ends.
 */
const val MIN_SPEED = 0.1f
const val MAX_SPEED = 4.0f

/** How far the ceiling drops on a refusal. Coarse on purpose: this is a search, not a tune. */
const val SPEED_BACKOFF = 0.5f
