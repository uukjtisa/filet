package dev.niccc2007.filet.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The sound every track and every video follows: an equaliser, a bass boost, and an app volume
 * on top of the phone's. One of these for the process - see `Sound`.
 *
 * @param eqOn the effects' one switch, which covers the bands, the bass boost, the space and the
 *   filter together. Off means none of them is in the signal at all, whatever they are set to.
 * @param bands the five bands' gains in dB, at [SoundMaths.BANDS_HZ], each within
 *   [SoundMaths.BAND_RANGE].
 * @param bass the bass boost, 0 to 100.
 * @param volume the app's own volume in percent of the phone's, 0 to [SoundMaths.MAX_VOLUME].
 *   100 is the phone's volume unchanged.
 * @param space a room around the sound - the platform's reverb - or none.
 * @param filter a fixed colour on top of the bands: through a wall, down a phone line, or none.
 */
data class SoundSettings(
    val eqOn: Boolean = false,
    val bands: List<Int> = SoundMaths.FLAT,
    val bass: Int = 0,
    val volume: Int = 100,
    val space: SoundMaths.Space = SoundMaths.Space.NONE,
    val filter: SoundMaths.Filter = SoundMaths.Filter.NONE,
)

/**
 * What a player and its session's effects are set to, for one [SoundSettings].
 *
 * @param level the player's own volume, 0 to 1. Everything at or under 100% is this and only
 *   this - a plain attenuation, nothing in the signal path.
 * @param boostMb the gain above 100%, in millibels, for the loudness effect; 0 for none.
 * @param eqInputDb the gain in front of the equaliser, which is the headroom its boosts need.
 * @param eqGains the equaliser's gain per band of [SoundMaths.EQ_EDGES_HZ], in dB, or null when
 *   there is nothing for it to do and it should not be in the signal at all.
 * @param space the reverb to put on the session, [SoundMaths.Space.NONE] for none.
 */
data class SoundPlan(
    val level: Float,
    val boostMb: Int,
    val eqInputDb: Float,
    val eqGains: FloatArray?,
    val space: SoundMaths.Space = SoundMaths.Space.NONE,
) {
    val boosts: Boolean get() = boostMb > 0
    val equalises: Boolean get() = eqGains != null
    val spaces: Boolean get() = space != SoundMaths.Space.NONE

    override fun equals(other: Any?): Boolean =
        other is SoundPlan && level == other.level && boostMb == other.boostMb && eqInputDb == other.eqInputDb &&
            space == other.space && (eqGains?.contentEquals(other.eqGains) ?: (other.eqGains == null))

    override fun hashCode(): Int = (((level.hashCode() * 31 + boostMb) * 31 + eqInputDb.hashCode()) * 31 +
        (eqGains?.contentHashCode() ?: 0)) * 31 + space.hashCode()
}

/**
 * The arithmetic of the sound settings, kept apart from Android so every number in it is tested.
 *
 * ## Where each part happens
 *
 * - **At or under 100%, the player's own volume.** An attenuation cannot clip, and nothing is
 *   added to the signal path: at the defaults no effect is attached at all, so the sound is the
 *   file's own, bit for bit.
 * - **Above 100%, the platform's loudness effect,** at [boostMb]. It is the one effect on this
 *   platform that limits by PEAK: it measures every sample, compresses above a knee at -8 dB
 *   with a 7:1 slope, attacks in a millisecond, and clamps at full scale. Its make-up gain goes
 *   in front of its compressor, so the limiter is not optional - it comes with the boost. The
 *   alternative, the dynamics effect's limiter, measures energy over a block, and a peak sits
 *   3 dB above that on a sine and 12 to 20 dB above it in music, so it lets peaks through.
 * - **The equaliser and the bass boost, on the dynamics effect's first EQ,** with the gain in
 *   front of it pulled down by the largest boost plus [EQ_MARGIN_DB]. So the equaliser only ever
 *   reshapes; it never adds level. The margin is for what any filter does to peaks: a cut can
 *   still raise a peak, the way a square wave with its top harmonics removed overshoots.
 *
 * ## The known limit
 *
 * No platform effect looks ahead. A full-scale attack out of silence at 2x reaches the loudness
 * effect's clamp for about its first one to two milliseconds before the compressor has caught
 * it - see [Limiter]. Sample-exact "never" needs the samples in hand before they play, which on
 * this platform means decoding them ourselves instead of through `MediaPlayer`.
 */
object SoundMaths {

    /** The five bands, as a phone equaliser has always shown them. */
    val BANDS_HZ = listOf(60, 230, 910, 3600, 14000)
    val FLAT: List<Int> = List(BANDS_HZ.size) { 0 }
    val BAND_RANGE = -12..12

    /**
     * The equaliser's own bands, finer than the five that are shown, so a slider moves a smooth
     * curve rather than a staircase: each is a band up to this frequency, in Hz.
     */
    val EQ_EDGES_HZ = floatArrayOf(45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f)

    const val MAX_VOLUME = 200

    /** The bass boost at 100, in dB, at the bottom of the range. */
    const val BASS_MAX_DB = 9f

    /** Where the bass boost has faded to nothing. */
    const val BASS_TOP_HZ = 250f

    /** Headroom kept beyond the largest boost, for what filtering does to peaks. */
    const val EQ_MARGIN_DB = 1.5f

    /**
     * Headroom kept while a [Space] is on, because a reverb ADDS: the platform's insert reverb
     * returns the dry sound plus the room's, so its output peaks above its input.
     *
     * 3 dB covers a room sound up to 7.7 dB under the dry one even where the two peaks line up
     * exactly, which they do not - a tail is the sound spread over a second, not stacked on it.
     * The platform's own presets, which are what [Space] uses, set the room far below that.
     */
    const val SPACE_HEADROOM_DB = 3f

    fun clampVolume(v: Int): Int = v.coerceIn(0, MAX_VOLUME)

    /** The volume rail's named points. 100 is the phone's own volume and the one that matters. */
    val VOLUME_STOPS = listOf(0, 50, 100, 150, 200)

    /**
     * A point on the volume rail, as a volume: in fives, and onto 100 from close by.
     *
     * 100 is the one value with nothing in the signal path, and 103% is a limiter switched on for
     * an increase nobody can hear - so the window around it is wider than the grain.
     */
    fun snapVolume(raw: Float): Int {
        val v = if (raw.isFinite()) raw.coerceIn(0f, MAX_VOLUME.toFloat()) else 100f
        if (abs(v - 100f) <= VOLUME_SNAP) return 100
        return clampVolume(((v / 5f).roundToInt() * 5))
    }

    /** How close to 100% counts as on it, in percent. */
    private const val VOLUME_SNAP = 4f

    /** The player's volume for an app volume: linear up to 100%, and 1 above it. */
    fun levelOf(volume: Int): Float = (clampVolume(volume).coerceAtMost(100) / 100f)

    /** The loudness effect's gain for an app volume, in millibels. 200% is twice the amplitude. */
    fun boostMbOf(volume: Int): Int {
        val v = clampVolume(volume)
        if (v <= 100) return 0
        return (2000.0 * log10(v / 100.0)).roundToInt()
    }

    /** Where each of the equaliser's own bands is centred: halfway, on a log scale, across it. */
    fun centres(): FloatArray = FloatArray(EQ_EDGES_HZ.size) { i ->
        val lo = if (i == 0) 20f else EQ_EDGES_HZ[i - 1]
        sqrt(lo * EQ_EDGES_HZ[i])
    }

    /**
     * A shown band's gains carried onto the equaliser's own bands, by straight lines between the
     * five on a log-frequency axis, held flat beyond the first and the last.
     */
    fun spread(bands: List<Int>): FloatArray {
        val g = BANDS_HZ.indices.map { (bands.getOrNull(it) ?: 0).coerceIn(BAND_RANGE).toFloat() }
        val at = BANDS_HZ.map { ln(it.toFloat()) }
        val c = centres()
        return FloatArray(EQ_EDGES_HZ.size) { i ->
            val x = ln(c[i])
            when {
                x <= at.first() -> g.first()
                x >= at.last() -> g.last()
                else -> {
                    val k = at.indexOfLast { it <= x }
                    val t = (x - at[k]) / (at[k + 1] - at[k])
                    g[k] + (g[k + 1] - g[k]) * t
                }
            }
        }
    }

    /** The bass boost on the equaliser's bands: full at the bottom, gone by [BASS_TOP_HZ]. */
    fun bassCurve(bass: Int): FloatArray {
        val amount = bass.coerceIn(0, 100) / 100f * BASS_MAX_DB
        val c = centres()
        val bottom = ln(c.first())
        val top = ln(BASS_TOP_HZ)
        return FloatArray(c.size) { i ->
            val t = ((ln(c[i]) - bottom) / (top - bottom)).coerceIn(0f, 1f)
            // A cosine fade, so the shelf has no corner a listener would hear as a resonance.
            amount * (0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * t))
        }
    }

    /** Everything a player and its effects are set to, for these settings. */
    fun plan(s: SoundSettings): SoundPlan {
        val level = levelOf(s.volume)
        val boost = boostMbOf(s.volume)
        if (!s.eqOn) return SoundPlan(level, boost, 0f, null)
        val spread = spread(s.bands)
        val bass = bassCurve(s.bass)
        val colour = s.filter.gains
        val gains = FloatArray(spread.size) { spread[it] + bass[it] + colour[it] }
        val space = s.space
        // Nothing to do is no effect at all, which is the only way to be sure it changes nothing.
        // A space alone still needs the equaliser, flat, for the headroom in front of the reverb.
        if (gains.all { abs(it) < 0.01f } && space == Space.NONE) return SoundPlan(level, boost, 0f, null)
        val most = gains.max()
        val forCurve = if (most > 0f) most + EQ_MARGIN_DB else 0f
        val forSpace = if (space != Space.NONE) SPACE_HEADROOM_DB else 0f
        return SoundPlan(level, boost, -(forCurve + forSpace), gains, space)
    }

    /**
     * The effects' net gain at their loudest - the equaliser's loudest band, plus what the space
     * is allowed to add - which [plan] keeps at or under zero.
     */
    fun netPeakDb(plan: SoundPlan): Float =
        (plan.eqGains?.max() ?: 0f) + plan.eqInputDb + if (plan.spaces) SPACE_HEADROOM_DB else 0f

    /**
     * A room around the sound: the platform's insert reverb, at one of its own presets.
     *
     * The platform's presets rather than parameters of Filet's own: they are the numbers every
     * phone's music app has offered for a decade, tuned by whoever wrote the reverb, and a "Hall"
     * that is somebody's first guess at a decay time is a worse hall.
     */
    enum class Space(val id: String, val label: String) {
        NONE("none", "None"),
        ROOM("room", "Room"),
        HALL("hall", "Hall");

        companion object {
            fun of(id: String?): Space = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    /**
     * A fixed colour on top of the bands, steeper than the five bands can draw: it is set on the
     * equaliser's ten own bands, per band of [EQ_EDGES_HZ].
     *
     * - **Muffled** is the next room: the wall takes the top away and leaves the bass, which is
     *   why a party next door is all kick drum. Flat to 355 Hz, then falling about 10 dB an
     *   octave and faster above 5.6 kHz.
     * - **Radio** is a small speaker or a phone line: roughly 300 Hz to 3.4 kHz, the telephone
     *   band, with the edges cut hard and a small lift in the middle where a cheap speaker rings.
     */
    enum class Filter(val id: String, val label: String, val gains: FloatArray) {
        NONE("none", "None", FloatArray(10)),
        MUFFLED("muffled", "Muffled", floatArrayOf(0f, 0f, 0f, 0f, -2f, -8f, -18f, -28f, -36f, -40f)),
        RADIO("radio", "Radio", floatArrayOf(-40f, -36f, -24f, -8f, 0f, 3f, 2f, -14f, -30f, -40f));

        companion object {
            fun of(id: String?): Filter = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    /**
     * The one-tap sounds: a curve, a bass amount, a space and a filter, set together.
     *
     * A look is not a mode the sound is put into. It sets the four, and the sound is named after
     * whichever look matches all four exactly - so moving any one of them afterwards reads as
     * "Custom" on its own, and nothing that is heard jumps when it does.
     */
    enum class Look(
        val label: String,
        val bands: List<Int>,
        val bass: Int = 0,
        val space: Space = Space.NONE,
        val filter: Filter = Filter.NONE,
    ) {
        FLAT("Flat", listOf(0, 0, 0, 0, 0)),
        BASS_HEAVY("Bass heavy", listOf(5, 3, 0, 0, 1), bass = 40),
        VOCAL("Vocal", listOf(-2, 0, 3, 4, 1)),
        WARM("Warm", listOf(3, 2, 0, -1, -2)),
        BRIGHT("Bright", listOf(0, 0, 0, 3, 5)),
        ROOM("Room", listOf(0, 0, 0, 0, 0), space = Space.ROOM),
        HALL("Hall", listOf(0, 0, 0, 0, 0), space = Space.HALL),
        MUFFLED("Muffled", listOf(0, 0, 0, 0, 0), filter = Filter.MUFFLED),
        RADIO("Radio", listOf(0, 0, 0, 0, 0), filter = Filter.RADIO);

        /** These settings with this look on them, and the effects switched on to hear it. */
        fun on(s: SoundSettings): SoundSettings =
            s.copy(eqOn = true, bands = bands, bass = bass, space = space, filter = filter)

        companion object {
            /** The look these settings are exactly, or null for "Custom". */
            fun of(s: SoundSettings): Look? = entries.firstOrNull {
                it.bands == s.bands && it.bass == s.bass && it.space == s.space && it.filter == s.filter
            }
        }
    }

    /** The bands as stored: "0,3,-2,0,1". Anything that does not read as five numbers is flat. */
    fun bandsOf(raw: String?): List<Int> {
        val parts = raw?.split(',')?.map { it.trim().toIntOrNull() } ?: return FLAT
        if (parts.size != BANDS_HZ.size || parts.any { it == null }) return FLAT
        return parts.map { it!!.coerceIn(BAND_RANGE) }
    }

    fun bandsText(bands: List<Int>): String = bands.joinToString(",")

    /**
     * A sample-by-sample model of the loudness effect's limiter, from its published source, so
     * the gain plan can be tested against what will actually be done with it.
     *
     * Make-up gain first, then a feed-forward compressor on the peak of each sample: above the
     * knee the level is pulled down by the slope, the reduction moves towards its target with a
     * one-millisecond attack and a fifteen-millisecond release, and the result is clamped at full
     * scale. The knee is the platform's highest, -8 dB, which is the least reduction it applies -
     * the worst case for clipping.
     */
    class Limiter(sampleRate: Int, boostMb: Int) {
        private val makeUp = 10.0.pow(boostMb / 2000.0).toFloat()
        private val knee = ln(10f.pow(KNEE_DB / 20f))
        private val slope = 1f / RATIO - 1f
        private val attack = exp(-1f / (ATTACK_S * sampleRate))
        private val release = exp(-1f / (RELEASE_S * sampleRate))
        private var state = 0f

        /** The last sample's level after the compressor and BEFORE the clamp, to see whether it was needed. */
        var unclamped = 0f
            private set

        fun process(x: Float): Float {
            val boosted = x * makeUp
            val level = ln(max(abs(boosted), MIN_LEVEL))
            val target = slope * max(level - knee, 0f)
            val a = if (target <= state) attack else release
            state = a * state + (1f - a) * target
            unclamped = boosted * exp(state)
            return unclamped.coerceIn(-1f, 1f)
        }

        companion object {
            const val KNEE_DB = -8f
            const val RATIO = 7f
            const val ATTACK_S = 0.001f
            const val RELEASE_S = 0.015f
            private const val MIN_LEVEL = 1e-6f
        }
    }
}
