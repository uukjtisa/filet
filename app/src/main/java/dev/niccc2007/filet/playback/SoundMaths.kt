package dev.niccc2007.filet.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One player's sound: an equaliser with a bass boost, an app volume on top of the phone's, and
 * the effects laid over it. Each player has its own - see `SoundState`.
 *
 * @param eqOn the equaliser's switch: the bands and the bass boost. Off means neither is in the
 *   signal, whatever they are set to. The effects have their own switches, one each.
 * @param bands the five bands' gains in dB, at [SoundMaths.BANDS_HZ], each within
 *   [SoundMaths.BAND_RANGE].
 * @param bass the bass boost, 0 to 100.
 * @param volume the app's own volume in percent of the phone's, 0 to [SoundMaths.MAX_VOLUME].
 *   100 is the phone's volume unchanged.
 * @param reverb a room around the sound, the platform's reverb: its strength is its size. See
 *   [SoundMaths.Space.sized].
 * @param muffled through a wall, as deep as its strength. See [SoundMaths.Filter].
 * @param radio a small speaker or a phone line, as deep as its strength.
 * @param underwater a head under the surface, as deep as its strength.
 * @param wide the platform's virtualizer, its strength the width. See [SoundMaths.WIDE_HEADROOM_DB].
 * @param evenOut a compressor, its strength how hard it works. See [SoundMaths.compression].
 * @param nightcore faster and higher together, its strength how much. See [SoundMaths.rateOf].
 * @param vaporwave slower and lower together, its strength how much.
 * @param orbit the sound swept from ear to ear, its strength how far. See [SoundMaths.orbitGains].
 */
data class SoundSettings(
    val eqOn: Boolean = false,
    val bands: List<Int> = SoundMaths.FLAT,
    val bass: Int = 0,
    val volume: Int = 100,
    val reverb: EffectLevel = EffectLevel(false, SoundMaths.REVERB_AMOUNT),
    val muffled: EffectLevel = EffectLevel(false, 100),
    val radio: EffectLevel = EffectLevel(false, 100),
    val underwater: EffectLevel = EffectLevel(false, 100),
    val wide: EffectLevel = EffectLevel(false, SoundMaths.WIDE_AMOUNT),
    val evenOut: EffectLevel = EffectLevel(false, SoundMaths.EVEN_OUT_AMOUNT),
    val nightcore: EffectLevel = EffectLevel(false, SoundMaths.TAPE_AMOUNT),
    val vaporwave: EffectLevel = EffectLevel(false, SoundMaths.TAPE_AMOUNT),
    val orbit: EffectLevel = EffectLevel(false, SoundMaths.ORBIT_AMOUNT),
)

/**
 * One effect's switch and strength, 0 to 100. The strength is kept while it is off, so turning
 * it back on brings back the effect that was turned off and not a default.
 */
data class EffectLevel(val on: Boolean, val amount: Int)

/**
 * What a player and its session's effects are set to, for one [SoundSettings].
 *
 * @param level the player's own volume, 0 to 1. Everything at or under 100% is this and only
 *   this - a plain attenuation, nothing in the signal path.
 * @param boostMb the gain above 100%, in millibels, for the loudness effect; 0 for none.
 * @param eqInputDb the gain in front of the equaliser, which is the headroom its boosts need.
 * @param eqGains the equaliser's gain per band of [SoundMaths.EQ_EDGES_HZ], in dB, or null when
 *   there is nothing for it to do and it should not be in the signal at all.
 * @param space the reverb to put on the session, at its size; [SoundMaths.Space.NONE] for none.
 * @param spaceHeadroomDb what that reverb may add on top of the dry sound, in dB, and so the part
 *   of [eqInputDb] that is for it; 0 without one.
 * @param wide the virtualizer's strength, 0 to 1000; 0 for none in the signal.
 * @param wideHeadroomDb the part of [eqInputDb] kept for the virtualizer; 0 without one.
 * @param evenOut the compressor, on the same dynamics effect as the equaliser, or null for none.
 * @param rate what the player's speed and its pitch are both multiplied by; 1 for neither.
 * @param orbit how far the sound swings from ear to ear, 0 to 1; 0 for no swing.
 */
data class SoundPlan(
    val level: Float,
    val boostMb: Int,
    val eqInputDb: Float,
    val eqGains: FloatArray?,
    val space: SoundMaths.Space = SoundMaths.Space.NONE,
    val spaceHeadroomDb: Float = 0f,
    val wide: Int = 0,
    val wideHeadroomDb: Float = 0f,
    val evenOut: SoundMaths.Compression? = null,
    val rate: Float = 1f,
    val orbit: Float = 0f,
) {
    val boosts: Boolean get() = boostMb > 0
    val equalises: Boolean get() = eqGains != null
    val spaces: Boolean get() = space != SoundMaths.Space.NONE
    val widens: Boolean get() = wide > 0

    override fun equals(other: Any?): Boolean =
        other is SoundPlan && level == other.level && boostMb == other.boostMb && eqInputDb == other.eqInputDb &&
            space == other.space && spaceHeadroomDb == other.spaceHeadroomDb &&
            wide == other.wide && wideHeadroomDb == other.wideHeadroomDb && evenOut == other.evenOut &&
            rate == other.rate && orbit == other.orbit &&
            (eqGains?.contentEquals(other.eqGains) ?: (other.eqGains == null))

    override fun hashCode(): Int {
        var h = level.hashCode()
        val parts = listOf(
            boostMb, eqInputDb, eqGains?.contentHashCode() ?: 0, space, spaceHeadroomDb,
            wide, wideHeadroomDb, evenOut, rate, orbit,
        )
        for (part in parts) h = h * 31 + part.hashCode()
        return h
    }
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
     * Headroom kept while a [Space] is on through the platform's PRESET reverb, the fallback on a
     * phone with no insert environmental one ([Reverb.PRESET]). A reverb ADDS: the insert form
     * returns the dry sound plus the room's, so its output peaks above its input.
     *
     * 3 dB covers a room sound up to 7.7 dB under the dry one even where the two peaks line up
     * exactly, which they do not - a tail is the sound spread over a second, not stacked on it.
     * The presets set the room 18 to 34 dB under the dry sound (see [Space]), far below that.
     * The environmental reverb's own headroom is per size, in [Room.headroomDb].
     */
    const val SPACE_HEADROOM_DB = 3f

    /** The reverb's most it can add, in millibels: AOSP's LVREV_MAX_REVERB_LEVEL. See [Space]. */
    const val REVERB_MAX_MB = 2000

    /** Wide's strength when it has never been set. */
    const val WIDE_AMOUNT = 60

    /** The virtualizer's strength per point of Wide's: the platform's runs 0 to 1000. */
    const val WIDE_PER_POINT = 10

    /**
     * Headroom kept in front of the equaliser while Wide is on: a margin, not a measurement. The
     * virtualizer mixes each side into the other through filters of its own, and how far that can
     * lift a peak is in the phone's library and nowhere in the API. 1.5 dB covers a sixth of the
     * other side landing exactly on a peak; if Wide is heard to crack on a loud track, this is the
     * number to raise.
     */
    const val WIDE_HEADROOM_DB = 1.5f

    /** Even out's strength when it has never been set: half, 2.5 to 1 above -21 dB. */
    const val EVEN_OUT_AMOUNT = 50

    /** Nightcore's and Vaporwave's strength when never set: 1.24x for one, 0.79x for the other. */
    const val TAPE_AMOUNT = 60

    /** How far Nightcore goes at full strength: 1.4x, speed and pitch together. */
    const val NIGHTCORE_SPAN = 0.4f

    /** How far Vaporwave goes at full strength: 0.65x. */
    const val VAPORWAVE_SPAN = 0.35f

    /** One trip from the left ear to the right and back, for 8D. */
    const val ORBIT_PERIOD_MS = 8_000L

    /**
     * 8D's swing when it has never been set: short of the whole way, where the far side is 16 dB
     * down rather than gone. See [orbitGains].
     */
    const val ORBIT_AMOUNT = 80

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

    /**
     * Everything a player and its effects are set to, for these settings.
     *
     * The equaliser's own switch covers the bands and the bass. Muffled, Radio and Underwater are
     * curves on the same equaliser, scaled by their strength and added on, each by its own switch.
     * The reverb is the platform's, at the size its strength names; Wide is its virtualizer, and
     * Even out a compressor on the equaliser's own effect. Nightcore and Vaporwave are the player's
     * rate ([rateOf]) and 8D its two volumes ([orbitGains]), so neither puts anything in the signal.
     *
     * @param reverb which of the platform's reverbs the phone gives, whose headroom differs.
     */
    fun plan(s: SoundSettings, reverb: Reverb = Reverb.ENVIRONMENTAL): SoundPlan {
        val level = levelOf(s.volume)
        val boost = boostMbOf(s.volume)
        val gains = FloatArray(EQ_EDGES_HZ.size)
        if (s.eqOn) {
            val spread = spread(s.bands)
            val bass = bassCurve(s.bass)
            for (i in gains.indices) gains[i] += spread[i] + bass[i]
        }
        for ((fx, filter) in listOf(s.muffled to Filter.MUFFLED, s.radio to Filter.RADIO, s.underwater to Filter.UNDERWATER)) {
            if (!fx.on) continue
            val depth = fx.amount.coerceIn(0, 100) / 100f
            for (i in gains.indices) gains[i] += filter.gains[i] * depth
        }
        // The reverb's strength is its size, and the smallest room is still a room.
        val space = if (s.reverb.on) Space.sized(s.reverb.amount) else Space.NONE
        val wide = if (s.wide.on) s.wide.amount.coerceIn(0, 100) * WIDE_PER_POINT else 0
        val evenOut = if (s.evenOut.on) compression(s.evenOut.amount) else null
        val rate = rateOf(s)
        val orbit = if (s.orbit.on) s.orbit.amount.coerceIn(0, 100) / 100f else 0f
        // Nothing to do is no effect at all, which is the only way to be sure it changes nothing.
        // A space or a width alone still needs the equaliser, flat, for the headroom in front of
        // it; the compressor is on the equaliser's own effect, so it needs it too.
        if (gains.all { abs(it) < 0.01f } && space == Space.NONE && wide == 0 && evenOut == null) {
            return SoundPlan(level, boost, 0f, null, rate = rate, orbit = orbit)
        }
        val most = gains.max()
        val forCurve = if (most > 0f) most + EQ_MARGIN_DB else 0f
        val forSpace = spaceHeadroomDb(space, reverb)
        val forWide = if (wide > 0) WIDE_HEADROOM_DB else 0f
        return SoundPlan(
            level, boost, -(forCurve + forSpace + forWide), gains, space, forSpace,
            wide, forWide, evenOut, rate, orbit,
        )
    }

    /**
     * What Nightcore and Vaporwave do to the player: its speed and its pitch, both multiplied by
     * this; 1 for neither.
     *
     * Speed and pitch moved together are a resampling - a tape played fast or slow - and the
     * platform does exactly that for them: it plays the track at a sample rate that much higher or
     * lower and stretches nothing in time, so there is nothing in it to warble at any strength.
     *
     * Multiplied into the speed set on the sheet rather than taking its place, so 1.5x with
     * Nightcore is half as fast again as Nightcore alone. The two are never on together (see
     * [Overlay.switched]); a settings file with both is played as both, multiplied.
     */
    fun rateOf(s: SoundSettings): Float {
        var r = 1f
        if (s.nightcore.on) r *= tapeRate(Overlay.NIGHTCORE, s.nightcore.amount)
        if (s.vaporwave.on) r *= tapeRate(Overlay.VAPORWAVE, s.vaporwave.amount)
        return r
    }

    /** The platform's pitch range: AUDIO_TIMESTRETCH_PITCH_MIN and _MAX, in AudioResamplerPublic.h. */
    const val PITCH_FLOOR = 0.25f
    const val PITCH_CEILING = 4f

    /**
     * The speed and the pitch a player is given: the sheet's [speed] and its [pitch] for that
     * speed, each multiplied by [rate], with the speed held to the fastest this phone has played
     * ([ceiling]) and the pitch to the platform's range. Past either, the rest is a stretch in
     * time rather than a rate the platform refuses.
     */
    fun played(speed: Float, pitch: Float, rate: Float, ceiling: Float): Pair<Float, Float> =
        (speed * rate).coerceAtMost(ceiling) to (pitch * rate).coerceIn(PITCH_FLOOR, PITCH_CEILING)

    /** Nightcore's or Vaporwave's own multiplier at [amount]; 1 for any other effect. */
    fun tapeRate(fx: Overlay, amount: Int): Float {
        val a = amount.coerceIn(0, 100) / 100f
        return when (fx) {
            Overlay.NIGHTCORE -> 1f + NIGHTCORE_SPAN * a
            Overlay.VAPORWAVE -> 1f - VAPORWAVE_SPAN * a
            else -> 1f
        }
    }

    /**
     * Even out's compressor: one band over the whole sound, on the multi-band stage of the same
     * dynamics effect as the equaliser, after its curve. A second dynamics effect on the session
     * would not be a second one: the platform hands back the one already there and moves its
     * control to the newcomer, and the equaliser's settings would quietly stop arriving.
     *
     * No make-up gain at all. It only ever brings the loud parts down, and the level it leaves is
     * the volume's to raise; make-up would be the one way an effect here could put a peak past
     * full scale, with nothing after it that looks ahead to catch it. One band over everything is a
     * single gain on each block, never more than one, so it cannot lift a sample either.
     *
     * Stronger is a lower threshold and a steeper ratio together: at half, everything over -21 dB
     * comes down at 2.5 to 1; at full, everything over -30 dB at 4 to 1. Ten milliseconds to catch
     * a note and three hundred to let it go, slow enough that it does not pump on a drum.
     *
     * @return null at 0, which is no compressor in the signal.
     */
    fun compression(amount: Int): Compression? {
        val d = amount.coerceIn(0, 100) / 100f
        if (d <= 0f) return null
        return Compression(
            thresholdDb = -12f - 18f * d,
            ratio = 1f + 3f * d,
            kneeDb = 6f,
            attackMs = 10f,
            releaseMs = 300f,
        )
    }

    /** A compressor as the dynamics effect takes one. See [compression]. */
    data class Compression(
        val thresholdDb: Float,
        val ratio: Float,
        val kneeDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
    )

    /**
     * 8D's two sides at [atMs]: the gain on the left and on the right, swept from one ear to the
     * other and back once every [ORBIT_PERIOD_MS].
     *
     * An equal-power pan, so the sound is as loud wherever it is: left squared plus right squared
     * is always one, and in the middle each side is at 0.71, 3 dB down. The swing is how far it
     * goes. At 1 it reaches each ear alone, which on a mix with a guitar on one side only means
     * the guitar goes when the sound is at the other ear; [ORBIT_AMOUNT] stops short of that.
     *
     * It moves the player's own volume on each side. The platform's mixer glides a volume change
     * across the block it mixes, so a step every few tens of milliseconds is a slope rather than
     * a staircase; the steepest step at full swing moves a side by 1.5% of full scale.
     */
    fun orbitGains(swing: Float, atMs: Long): Pair<Float, Float> {
        val phase = atMs.mod(ORBIT_PERIOD_MS).toFloat() / ORBIT_PERIOD_MS
        val theta = PI / 4 * (1 + swing.coerceIn(0f, 1f) * sin(2 * PI * phase))
        return cos(theta).toFloat() to sin(theta).toFloat()
    }

    /**
     * The effects' net gain at their loudest - the equaliser's loudest band, plus what the space
     * and the width are allowed to add - which [plan] keeps at or under zero.
     */
    fun netPeakDb(plan: SoundPlan): Float =
        (plan.eqGains?.max() ?: 0f) + plan.eqInputDb + plan.spaceHeadroomDb + plan.wideHeadroomDb

    /**
     * Which of the platform's reverbs a phone gives as an insert on one session: the
     * environmental one, set to Filet's own [Room]s, or else the preset one, at its presets.
     */
    enum class Reverb { ENVIRONMENTAL, PRESET }

    /**
     * The reverb to use, from what the phone has as an insert effect; null for neither, and then
     * there is no Reverb to offer. The environmental one first: see [Space] for why the presets
     * are only what is left.
     */
    fun reverbFor(environmental: Boolean, preset: Boolean): Reverb? = when {
        environmental -> Reverb.ENVIRONMENTAL
        preset -> Reverb.PRESET
        else -> null
    }

    /** What a [space] is allowed to add on top of the dry sound, in dB, on [reverb]; never below 0. */
    fun spaceHeadroomDb(space: Space, reverb: Reverb): Float = when {
        space == Space.NONE -> 0f
        reverb == Reverb.PRESET -> SPACE_HEADROOM_DB
        else -> room(space)?.headroomDb ?: 0f
    }

    /**
     * A room as the environmental reverb's parameters. All millibels; ratios in thousandths, as
     * the platform takes them.
     *
     * @param decayHfRatio how fast the top dies against the whole: 1000 is evenly, under it the
     *   top goes first, which is what air and soft walls do and what a hall sounds like.
     * @param roomLevelMb the master level of everything the room adds, 0 at most.
     * @param roomHfLevelMb the top of it, a little down, so the room is not a hiss.
     * @param reverbLevelMb the late tail, -9000 to +2000.
     */
    data class Room(
        val decayMs: Int,
        val decayHfRatio: Int,
        val reverbLevelMb: Int,
        val roomLevelMb: Int = 0,
        val roomHfLevelMb: Int = -300,
        val diffusion: Int = 1000,
        val density: Int = 1000,
    ) {
        /**
         * The room's level in the mix against the dry sound, 0 to 1: the wrapper's
         * ReverbConvertLevel(room + reverb - [REVERB_MAX_MB]), whose table is
         * 2000 * log10(level / 100) millibels, so this is that level over 100, unrounded.
         */
        val wet: Float get() = 10f.pow((roomLevelMb + reverbLevelMb - REVERB_MAX_MB) / 2000f).coerceIn(0f, 1f)

        /**
         * What the room can add on top of the dry sound at the worst, in dB: both peaks lined up,
         * 20 * log10(1 + [wet]). The wrapper sends only 0.75 of the input into the room, which
         * this does not count on - that send is the wrapper's own and nowhere in the API, and a
         * phone that sends all of it must not clip.
         */
        val headroomDb: Float get() = (20f * log10(1f + wet)).coerceAtLeast(0f)
    }

    /**
     * Each size as the environmental reverb's parameters, or null for [Space.NONE].
     *
     * The decay doubles roughly every step and the room comes up with it, so each size is longer
     * AND louder than the one under it: Small room is a quarter of the dry level and dies in
     * 0.6 s, Large hall is level with the dry sound and takes 4 s. The tail darkens as it grows.
     */
    fun room(space: Space): Room? = when (space) {
        Space.NONE -> null
        Space.SMALL_ROOM -> Room(decayMs = 600, decayHfRatio = 800, reverbLevelMb = 800)
        Space.ROOM -> Room(decayMs = 1000, decayHfRatio = 800, reverbLevelMb = 1100)
        Space.LARGE_ROOM -> Room(decayMs = 1600, decayHfRatio = 750, reverbLevelMb = 1400)
        Space.MEDIUM_HALL -> Room(decayMs = 2600, decayHfRatio = 700, reverbLevelMb = 1700)
        Space.HALL -> Room(decayMs = 4000, decayHfRatio = 650, reverbLevelMb = 2000)
    }

    /**
     * A room around the sound: the platform's insert reverb, at one of five sizes.
     *
     * ## Rooms of Filet's own, not the platform's presets
     *
     * Bug identified here: on the platform's presets every size was all but silent, and the
     * largest was the quietest. The phone's reverb is AOSP's EffectReverb wrapper, NXP's build of
     * it, and it sets the room's level in the mix to ReverbConvertLevel(room level + reverb
     * level - 2000 mB), a table of 2000 * log10(level / 100) mB for level 0 to 100. The
     * presets' own numbers go in:
     *
     * - LARGEHALL is room -400 and reverb -1400: -400 - 1400 - 2000 = -3800 mB, which the table
     *   rounds to level 2 of 100. 20 * log10(0.02) is -34 dB: the hall plays 34 dB under the dry
     *   sound, and that after the wrapper has sent only 0.75 of the input into it.
     * - SMALLROOM is room -400 and reverb +500: -1900 mB, level 12, -18 dB.
     *
     * The numbers are AOSP's EffectReverb.cpp. This phone's library cannot be read to check
     * them, and what it plays matches them: an insert reverb that is on, not suspended, on the
     * route the sound takes, and not heard.
     *
     * So each size is the environmental reverb's [Room], with the room at 0 mB and the reverb
     * between +800 and +2000, level 25 to 100. Where a phone has no insert environmental reverb
     * ([reverbFor]), each size is the preset nearest it, quiet as they are, with its headroom
     * [SPACE_HEADROOM_DB]; quiet is still better than no Reverb at all.
     *
     * Five sizes, smallest to largest, are the reverb's strength (see [sized]). The ids are the
     * stored ones from when there were two: "room" was always the medium room and "hall" the
     * large hall, so a stored room is still the room it was.
     *
     * @param amount the strength this size sits at on the reverb's rail.
     */
    enum class Space(val id: String, val label: String, val amount: Int) {
        NONE("none", "None", -1),
        SMALL_ROOM("smallroom", "Small room", 0),
        ROOM("room", "Room", 25),
        LARGE_ROOM("largeroom", "Large room", 50),
        MEDIUM_HALL("mediumhall", "Hall", 75),
        HALL("hall", "Large hall", 100);

        companion object {
            fun of(id: String?): Space = entries.firstOrNull { it.id == id } ?: NONE

            /** The sizes in rail order, smallest first. */
            val SIZES: List<Space> = listOf(SMALL_ROOM, ROOM, LARGE_ROOM, MEDIUM_HALL, HALL)

            /** The size a reverb strength names: five stops, 0 to 100 in quarters. */
            fun sized(amount: Int): Space = SIZES[(amount.coerceIn(0, 100) / 25f).roundToInt()]
        }
    }

    /**
     * A fixed colour, steeper than the five bands can draw: set on the equaliser's ten own bands,
     * per band of [EQ_EDGES_HZ], at full strength. An effect's strength scales it.
     *
     * - **Muffled** is the next room: the wall takes the top away and leaves the bass, which is
     *   why a party next door is all kick drum. Flat to 355 Hz, then falling about 10 dB an
     *   octave and faster above 5.6 kHz.
     * - **Radio** is a small speaker or a phone line: roughly 300 Hz to 3.4 kHz, the telephone
     *   band, with the edges cut hard and a small lift in the middle where a cheap speaker rings.
     * - **Underwater** is a head under the surface: the low end swells a little, as it does in a
     *   bath, and everything above the low mids goes - lower and steeper than the wall, from 710 Hz.
     *
     * [NONE] and the first two ids are what was stored before the effects had a switch each, and
     * are read back from an older setting by [filtersOf]. Underwater came after, and never had one.
     */
    enum class Filter(val id: String, val label: String, val gains: FloatArray) {
        NONE("none", "None", FloatArray(10)),
        MUFFLED("muffled", "Muffled", floatArrayOf(0f, 0f, 0f, 0f, -2f, -8f, -18f, -28f, -36f, -40f)),
        RADIO("radio", "Radio", floatArrayOf(-40f, -36f, -24f, -8f, 0f, 3f, 2f, -14f, -30f, -40f)),
        UNDERWATER("underwater", "Underwater", floatArrayOf(2f, 3f, 4f, 2f, -6f, -18f, -30f, -40f, -40f, -40f));

        companion object {
            fun of(id: String?): Filter = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    /** The reverb's strength when it has never been set: the medium room, the old "Room". */
    const val REVERB_AMOUNT = 25

    /**
     * The equaliser's named curves: the five bands and the bass, set together.
     *
     * A preset is not a mode. It moves the sliders, and the curve is named after whichever
     * preset matches all six exactly, so touching any slider afterwards reads as "Custom" on its
     * own and nothing heard jumps when it does.
     *
     * The curves follow the long-standing ones phones and players ship for these names, on the
     * five classic bands (60 Hz to 14 kHz), and none goes past 6 dB: the equaliser only ever
     * reshapes, so a bigger boost is a quieter track, not a louder band.
     */
    enum class EqPreset(val label: String, val bands: List<Int>, val bass: Int = 0) {
        FLAT("Flat", listOf(0, 0, 0, 0, 0)),
        ACOUSTIC("Acoustic", listOf(4, 3, 1, 3, 2)),
        BASS_BOOST("Bass boost", listOf(3, 1, 0, 0, 0), bass = 60),
        BASS_CUT("Bass cut", listOf(-6, -3, 0, 0, 0)),
        CLASSICAL("Classical", listOf(5, 3, -2, 4, 4)),
        DANCE("Dance", listOf(6, 0, 2, 4, 1)),
        ELECTRONIC("Electronic", listOf(4, 1, -1, 1, 4), bass = 20),
        HIP_HOP("Hip-hop", listOf(5, 3, 0, 1, 3), bass = 20),
        JAZZ("Jazz", listOf(4, 2, -2, 2, 5)),
        POP("Pop", listOf(-1, 2, 5, 1, -2)),
        RNB("R&B", listOf(5, 4, -2, 2, 3)),
        ROCK("Rock", listOf(5, 3, -1, 3, 5)),
        SMALL_SPEAKERS("Small speakers", listOf(4, 4, 1, -1, -3)),
        SPOKEN_WORD("Spoken word", listOf(-4, -1, 3, 4, 0)),
        TREBLE_BOOST("Treble boost", listOf(0, 0, 0, 3, 6)),
        TREBLE_CUT("Treble cut", listOf(0, 0, 0, -3, -6)),
        VOCAL("Vocal", listOf(-2, 0, 3, 4, 1));

        /** These settings with this curve on them, and the equaliser switched on to hear it. */
        fun on(s: SoundSettings): SoundSettings = s.copy(eqOn = true, bands = bands, bass = bass)

        companion object {
            /** The preset these bands and this bass are exactly, or null for "Custom". */
            fun of(s: SoundSettings): EqPreset? = entries.firstOrNull { it.bands == s.bands && it.bass == s.bass }
        }
    }

    /**
     * The effects laid over the sound, one chip each in the sheet, each with its own switch and
     * strength and none under a master switch: they stack.
     *
     * Only what is real today, on the platform's effects and the player's own controls. What needs
     * the samples in hand - a lo-fi, a vinyl, an echo, a karaoke - joins this list with the engine
     * that can make it, not before. 8D is here as the sweep alone: the player's two volumes can
     * move the sound from ear to ear, and the binaural kind, in front and behind, is the engine's.
     *
     * @param part what the phone has to give for it to be heard; null for what every player does
     *   with its own rate and volume.
     * @param stops the strength's named points, where it has them: the reverb's are its sizes.
     */
    enum class Overlay(val id: String, val label: String, val about: String, val part: SoundPart?, val stops: List<String>?) {
        REVERB(
            "reverb", "Reverb", "A room around the sound, from a small room to a large hall.",
            SoundPart.SPACE, Space.SIZES.map { it.label },
        ),
        MUFFLED(
            "muffled", "Muffled", "Through a wall: the top goes and the bass stays.",
            SoundPart.EQUALISER, null,
        ),
        RADIO(
            "radio", "Radio", "A small speaker or a phone line: the middle, with both ends cut.",
            SoundPart.EQUALISER, null,
        ),
        UNDERWATER(
            "underwater", "Underwater", "Under the surface: the low end swells and the rest goes dull.",
            SoundPart.EQUALISER, null,
        ),
        WIDE(
            "wide", "Wide", "Spreads the sound out past your ears. Headphones only; the speaker stays as it is.",
            SoundPart.WIDE, null,
        ),
        EVEN_OUT(
            "evenout", "Even out", "Brings the loud parts down toward the quiet ones, for a night or a noisy bus.",
            SoundPart.EQUALISER, null,
        ),
        NIGHTCORE(
            "nightcore", "Nightcore", "Faster and higher together, like a tape played fast.",
            null, null,
        ),
        VAPORWAVE(
            "vaporwave", "Vaporwave", "Slower and lower together, like a tape played slow.",
            null, null,
        ),
        ORBIT(
            "8d", "8D", "The sound sweeps from ear to ear and back every 8 seconds. For headphones.",
            null, null,
        );

        fun of(s: SoundSettings): EffectLevel = when (this) {
            REVERB -> s.reverb
            MUFFLED -> s.muffled
            RADIO -> s.radio
            UNDERWATER -> s.underwater
            WIDE -> s.wide
            EVEN_OUT -> s.evenOut
            NIGHTCORE -> s.nightcore
            VAPORWAVE -> s.vaporwave
            ORBIT -> s.orbit
        }

        fun set(s: SoundSettings, level: EffectLevel): SoundSettings {
            val l = level.copy(amount = snap(level.amount.toFloat()))
            return when (this) {
                REVERB -> s.copy(reverb = l)
                MUFFLED -> s.copy(muffled = l)
                RADIO -> s.copy(radio = l)
                UNDERWATER -> s.copy(underwater = l)
                WIDE -> s.copy(wide = l)
                EVEN_OUT -> s.copy(evenOut = l)
                NIGHTCORE -> s.copy(nightcore = l)
                VAPORWAVE -> s.copy(vaporwave = l)
                ORBIT -> s.copy(orbit = l)
            }
        }

        /**
         * [s] with this effect's strength at [amount] and its switch where it was.
         *
         * A strength is set while the effect is off, and is what plays when it is switched on.
         * Setting one does not switch it on (D-19): looking at an effect, or getting it ready, is
         * not asking to hear it.
         */
        fun withAmount(s: SoundSettings, amount: Int): SoundSettings = set(s, of(s).copy(amount = amount))

        /**
         * [s] with this effect switched [on], at the strength it was left at.
         *
         * Nightcore and Vaporwave run the tape opposite ways, so switching one on switches the
         * other off rather than leaving the two to cancel into something that is neither. Only
         * ever off: nothing but an effect's own switch turns it on (D-19).
         */
        fun switched(s: SoundSettings, on: Boolean): SoundSettings {
            val next = set(s, of(s).copy(on = on))
            val other = when (this) {
                NIGHTCORE -> VAPORWAVE
                VAPORWAVE -> NIGHTCORE
                else -> null
            }
            return if (on && other != null) other.set(next, other.of(next).copy(on = false)) else next
        }

        /** A point on the strength's rail as a strength: the reverb's five sizes, the rest in fives. */
        fun snap(raw: Float): Int {
            val v = if (raw.isFinite()) raw.coerceIn(0f, 100f) else 100f
            return if (stops != null) ((v / 25f).roundToInt() * 25) else ((v / 5f).roundToInt() * 5)
        }

        /**
         * Whether it is heard at [level]: on, and at a strength that does something. The reverb's
         * smallest size is a room; a filter at 0 is no filter.
         */
        fun heard(level: EffectLevel): Boolean = level.on && (this == REVERB || level.amount > 0)

        /** The strength as the sheet says it: the reverb's size, the tape's rate, or a percentage. */
        fun amountLabel(amount: Int): String = when (this) {
            REVERB -> Space.sized(amount).label
            NIGHTCORE, VAPORWAVE -> {
                val rate = tapeRate(this, amount)
                String.format(java.util.Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"
            }
            else -> "${amount.coerceIn(0, 100)}%"
        }

        companion object {
            fun of(id: String?): Overlay? = entries.firstOrNull { it.id == id }
        }
    }

    // ── how the effects are stored ──
    //
    // In the keys the space and the filter always had, so an older settings file still lands
    // where it belongs: the reverb in the space key, and every other effect by name in the
    // filter key, whose name dates from when Muffled and Radio were all it held. A level is "1:25" (on, at 25) or "0:100" (off, kept at 100). Before the
    // effects had a switch each, the keys held a name - "hall", "muffled" - heard only while the
    // equaliser's one switch was on; read back, that name is the effect at today's strength, on
    // exactly when the switch was, so nothing that was heard goes quiet and nothing that was not
    // starts playing.

    /** A level as stored: "1:25". */
    fun levelText(l: EffectLevel): String = (if (l.on) "1" else "0") + ":" + l.amount.coerceIn(0, 100)

    /** A level stored as "1:25", or null for anything else. */
    fun levelOf(raw: String?): EffectLevel? {
        val parts = raw?.trim()?.split(':') ?: return null
        if (parts.size != 2) return null
        val on = when (parts[0]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val amount = parts[1].toIntOrNull() ?: return null
        return EffectLevel(on, amount.coerceIn(0, 100))
    }

    /** The reverb, from what the space key holds. [eqOn] is the switch an older name was heard under. */
    fun reverbOf(raw: String?, eqOn: Boolean): EffectLevel {
        levelOf(raw)?.let { return it }
        val size = Space.of(raw)
        return if (size == Space.NONE) EffectLevel(false, REVERB_AMOUNT) else EffectLevel(eqOn, size.amount)
    }

    /** The effects the filter key keeps: all but the reverb, which has the space key. */
    val FILTER_KEY_EFFECTS: List<Overlay> = Overlay.entries.filter { it != Overlay.REVERB }

    /** Every effect but the reverb as stored under the filter key: "muffled=1:60,radio=0:100,...". */
    fun filtersText(s: SoundSettings): String =
        FILTER_KEY_EFFECTS.joinToString(",") { it.id + "=" + levelText(it.of(s)) }

    /**
     * The effects the filter key holds, by effect; one it does not name is not in the map.
     * [eqOn] as for [reverbOf]. A name this build does not know is passed over, so a settings
     * file from a later one still gives back the rest.
     */
    fun filtersOf(raw: String?, eqOn: Boolean): Map<Overlay, EffectLevel> {
        if (raw != null && '=' in raw) {
            return raw.split(',').mapNotNull { part ->
                val kv = part.split('=', limit = 2)
                val fx = Overlay.of(kv[0].trim())?.takeIf { it in FILTER_KEY_EFFECTS }
                if (kv.size != 2 || fx == null) null else levelOf(kv[1])?.let { fx to it }
            }.toMap()
        }
        val off = EffectLevel(false, 100)
        return when (Filter.of(raw)) {
            Filter.MUFFLED -> mapOf(Overlay.MUFFLED to EffectLevel(eqOn, 100), Overlay.RADIO to off)
            Filter.RADIO -> mapOf(Overlay.MUFFLED to off, Overlay.RADIO to EffectLevel(eqOn, 100))
            Filter.NONE, Filter.UNDERWATER -> emptyMap()
        }
    }

    /** [s] with the effects the filter key holds; one it does not name stays as in [s]. */
    fun withFilters(s: SoundSettings, raw: String?, eqOn: Boolean): SoundSettings =
        filtersOf(raw, eqOn).entries.fold(s) { acc, (fx, level) -> fx.set(acc, level) }

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
