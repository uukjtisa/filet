package dev.niccc2007.filet.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * Five readings of what the music is doing, on one clock, for everything that answers it.
 *
 * ## Why five and not one
 *
 * They are different questions and a single envelope answered them all badly. The glow wants
 * a punch with a short tail. The wave wants to be FLAT through a calm passage and violent on
 * a transient, which is a much faster attack and a much slower release. A travelling ring
 * wants to fire rarely, on the big ones only. And a figure drawn around the artwork wants the
 * loudness, which is continuous, not the onsets, which are nearly all zero.
 *
 * One clock, though. Five coroutines would drift by a frame or two and the face would beat
 * slightly out of step with itself.
 *
 * @param level the punch, 0 to 1, decaying over [DECAY_MS].
 * @param swing the same punch measured against what this track has been doing LATELY, so it
 *   is signed: negative in the gaps, positive on a hit, and averaging about zero. See
 *   [beatSwing].
 * @param blast the wave's amplitude: instant to rise, quick to fall, and zero through anything
 *   that is not a rise. See [WAVE_RELEASE_MS].
 * @param shock a rare impulse, 1 the moment a hit lands well above this track's own normal
 *   and 0 by [SHOCK_MS] later. Nothing else fires it; it is what makes a big beat read as an
 *   impact rather than as a bigger wobble.
 * @param loud how loud it is right now, smoothed - the resting shape, not the hits.
 */
class BeatSignal internal constructor(
    val level: State<Float>,
    val swing: State<Float>,
    val blast: State<Float>,
    val shock: State<Float>,
    val loud: State<Float>,
)

/**
 * The beat, read from the map and lined up with what is actually coming out of the speaker.
 *
 * ## Where the position is read from, and the lateness that is NOT guesswork any more
 *
 * There used to be a Timing slider here, defaulting to reading the map a hundred and forty
 * milliseconds ahead of the player. It was a knob in place of an answer: a light that needs
 * calibrating by hand on every phone is a light that is wrong on every phone nobody has
 * calibrated. Each part of the lateness it was covering for is computable.
 *
 * - **The player reports the decoder, not the ear.** `MediaPlayer.getCurrentPosition` counts
 *   frames that have left the app. What is being HEARD left some milliseconds ago - the mixer
 *   buffer, a speaker DSP, a Bluetooth hop. [BeatLatency] derives that from the device.
 * - **An onset sits later inside its bucket than the bucket's index implies.** The window is
 *   1024 samples and the hop is 20 milliseconds, so the content that caused a rise is at the
 *   END of the window, not its start. [BeatMap.biasMs] is that offset in milliseconds, and it
 *   is arithmetic over the window and the sample rate - which is also why it moved on a long
 *   track, where the rate used to halve.
 * - **The flow was two hundred milliseconds stale.** Asking the player costs one call. The
 *   flow is the fallback now, not the source.
 *
 * What remains genuinely unknowable is a Bluetooth earbud's own pipeline, and that is one
 * constant with its reasoning written beside it rather than a control. See
 * [BeatLatency.A2DP_MS].
 *
 * ## Cost model
 *
 * - **Trigger:** a map exists, [on], [playing], the Activity is on screen and the OS animation
 *   switch is on - see [shouldAnimate]. The loop exists only while all of those hold. It used
 *   to run whenever a map and the switch were there, so it stepped through every pause and,
 *   because `delay` is not the frame clock, through every minute of screen-off playback.
 * - **Rate:** `PlayerDials.pulseFps` - 60 Hz on a capable phone, 40 and 30 below. Each step is
 *   one `positionNow()` (a JNI call into the player, no binder), two array reads in the map,
 *   one [BeatFollower.step] (a handful of `exp`s) and five float state writes.
 * - **Allocates:** nothing per step. The diagnostics line is built only in a debug build, once
 *   every [LOG_EVERY_MS].
 * - **Invalidates:** the draw phase of every surface reading a signal, never a composition.
 *
 * Paused or out of sight, the five outputs go to rest (zero swing is the glow's resting
 * level) and the follower keeps its gain and floor, so resuming carries on from the track's
 * own measured range instead of re-learning it.
 */
@Composable
fun rememberBeatSignal(
    beats: BeatMap?,
    playback: Playback,
    on: Boolean,
    /** Whether the player is playing. A paused track has no beat to follow. */
    playing: Boolean,
    /** How readily a rise counts as a beat. Fixed at [PULSE_SENSE]. */
    sensitivity: Float = 1f,
): BeatSignal {
    val level = remember { mutableFloatStateOf(0f) }
    val swing = remember { mutableFloatStateOf(0f) }
    val blast = remember { mutableFloatStateOf(0f) }
    val shock = remember { mutableFloatStateOf(0f) }
    val loud = remember { mutableFloatStateOf(0f) }
    val signal = remember { BeatSignal(level, swing, blast, shock, loud) }

    /**
     * The arithmetic, which outlives every restart of the loop below.
     *
     * A better map arrives every few hundred milliseconds while a track is being measured, and
     * each one restarts the effect - so anything that has to be continuous cannot be a local.
     * It was a FloatArray of five, which is the shape that invites getting an index wrong; it
     * is an object now, and the object is testable without a phone. See [BeatFollower].
     */
    val follower = remember { BeatFollower() }
    // Remembered, like the follower, so a stall that spans a restart of the loop below is
    // still measured from the last real step rather than reset to nothing.
    val watch = remember { DropoutWatch() }
    val animated = rememberSystemAnimations()
    val onScreen = rememberOnScreen()
    val fps = rememberPlayerDials().pulseFps
    val context = LocalContext.current
    val wanted = on && beats != null && !beats.empty
    val live = wanted && shouldAnimate(onScreen, playing, animated)

    LaunchedEffect(live, wanted, beats, sensitivity, fps) {
        if (!live) {
            // Switched off or no map: forget the track. Merely paused or out of sight: keep
            // what the follower has learned about it, and only put the outputs at rest.
            if (!wanted) follower.reset()
            // Not stepping here is deliberate, so it must not later read as a stall.
            watch.rest()
            level.floatValue = 0f
            swing.floatValue = 0f
            blast.floatValue = 0f
            shock.floatValue = 0f
            loud.floatValue = 0f
            return@LaunchedEffect
        }
        val map = beats!!
        // Read per track rather than once for the process: earbuds go in halfway through a
        // song, and the right answer changes under whoever did that.
        val output = BeatLatency.outputMs(context)
        val bias = map.biasMs
        dev.niccc2007.filet.data.Diag.log(
            "beat",
            "map covers ${map.coveredMs}ms of ${playback.state.value.durationMs}ms, " +
                "bias=${bias}ms output=${output}ms sense=$sensitivity",
        )
        var reported = -1L
        var stampedAt = android.os.SystemClock.elapsedRealtime()
        var at = stampedAt
        var sinceLog = 0f
        dev.niccc2007.filet.log.FiletLog.count("beat.restart")
        val ticking = dev.niccc2007.filet.log.FiletLog.loop("beat.follower", 1000L / fps)
        try { while (true) {
            val state = playback.state.value
            val now = android.os.SystemClock.elapsedRealtime()
            // ASK THE PLAYER, and only fall back to the flow when there is no answer. The flow
            // is written five times a second, so reading it adds up to two hundred milliseconds
            // of the last tick's age to every reading.
            val livePos = playback.positionNow()
            if (livePos != null) {
                reported = livePos
                stampedAt = now
            } else if (state.positionMs != reported) {
                reported = state.positionMs
                stampedAt = now
            }
            // Clamped: a loop suspended while the screen was off comes back with a gap of
            // minutes, and exp(-huge) is zero - which is right - but the clamp also keeps the
            // arithmetic away from the edges.
            val gap = (now - at).coerceIn(0L, 250L).toFloat()
            at = now
            // The bucket whose sound is in the room RIGHT NOW. BEHIND the reported position,
            // not ahead of it: the sound is delayed, so the light has to be delayed with it,
            // and the only way to delay a light is to drive it from an earlier part of the
            // track.
            val ear = reported + (now - stampedAt) - output - bias
            // ── a position past the end of a PARTIAL map is not silence ──
            //
            // The opening of a track is measured almost at once and the rest follows, so for
            // the first moments the map covers only a few seconds of it - and `strengthAt`
            // answered anything past that with 0, which is the same answer it gives for "no
            // beat here". The light went out at the edge of the measured part and stayed out
            // until the rest arrived. Clamped to the last bucket that exists instead: briefly
            // it pulses on the wrong part of the track, which nobody can see, where the
            // alternative is a light that plainly stops, which everybody can.
            val covered = map.coveredMs
            val readAt =
                if (covered > 0L && ear >= covered) covered - BeatMap.BUCKET_MS else ear
            val raw =
                if (!state.playing) 0f
                else (map.strengthAt(readAt) * sensitivity).coerceIn(0f, 1f)
            val loudNow = if (!state.playing) 0f else map.levelAt(readAt)

            follower.step(raw, loudNow, gap)
            ticking.tick()

            // ── the drop-out, measured rather than argued about ──
            //
            // Reported three times as the pulse vanishing for moments. Three causes are
            // possible and they look identical on screen: the loop stopped stepping, the map had
            // nothing for where the track was, or hits arrived and the follower flattened them.
            // [DropoutWatch] tells them apart from the numbers of the quiet stretch itself and
            // writes one line naming which, plus a counter per cause in the periodic stats.
            val dropped = watch.step(
                now = now,
                pastMap = covered > 0L && ear >= covered,
                raw = raw,
                loud = loudNow,
                blast = follower.blast,
                swing = follower.swing,
            )
            if (dropped != null) {
                dev.niccc2007.filet.log.FiletLog.count("beat.dropout." + dropped.cause.tag)
                dev.niccc2007.filet.log.FiletLog.i("beat") {
                    "pulse dropped for ${dropped.quietMs}ms: ${dropped.cause} " +
                        "(longest gap ${dropped.longestGapMs}ms, past map ${dropped.pastMapMs}ms, " +
                        "max raw %.2f, mean loud %.2f, map %dms, ear %dms) ".format(
                            dropped.maxRaw, dropped.meanLoud, covered, ear,
                        ) + follower.describe()
                }
            }

            level.floatValue = follower.level
            swing.floatValue = follower.swing
            blast.floatValue = follower.blast
            shock.floatValue = follower.shock
            loud.floatValue = follower.loud

            // Once every few seconds, and only in a debug build - see `Diag`. A report about
            // this can then carry numbers rather than adjectives, which is the difference
            // between fixing it and guessing at it twice more.
            sinceLog += gap
            if (DEBUG_LOG && sinceLog >= LOG_EVERY_MS) {
                sinceLog = 0f
                dev.niccc2007.filet.data.Diag.log(
                    "beat",
                    "at=${readAt}ms raw=%.2f ".format(raw) + follower.describe(),
                )
            }
            delay(1000L / fps)
        } } finally {
            ticking.close()
        }
    }
    return signal
}

/**
 * Why the pulse went flat, from the numbers of the flat stretch. Pure; see [classifyDropout].
 */
enum class DropoutCause(val tag: String) {
    /** The follower did not step: a gap between steps long enough to see. */
    STALLED("stalled"),

    /** The position was past the end of the map for most of it - the map was still partial. */
    PAST_MAP("past-map"),

    /** The music was audible and the map had no onsets for it. */
    EMPTY_MAP("empty-map"),

    /** Onsets were read and the follower's output stayed flat anyway: gain or mean. */
    FLATTENED("flattened"),

    /** The music itself was quiet. Not a fault. */
    QUIET("quiet"),
}

/**
 * The verdict for one flat stretch. Pure, and ordered: a stall explains everything after it,
 * and a missing map explains an empty reading, so those are checked first.
 */
fun classifyDropout(
    quietMs: Long,
    longestGapMs: Long,
    pastMapMs: Long,
    maxRaw: Float,
    meanLoud: Float,
): DropoutCause = when {
    longestGapMs >= STALL_MS -> DropoutCause.STALLED
    pastMapMs * 2 >= quietMs -> DropoutCause.PAST_MAP
    maxRaw >= HIT_RAW -> DropoutCause.FLATTENED
    meanLoud >= AUDIBLE -> DropoutCause.EMPTY_MAP
    else -> DropoutCause.QUIET
}

/** A gap between steps this long is a stall: four or more missed steps even at 30 Hz. */
const val STALL_MS = 150L

/** An onset strength this high, read during a flat stretch, is a hit the follower swallowed. */
const val HIT_RAW = 0.15f

/** And a mean loudness this high is music that ought to have had something in it. */
const val AUDIBLE = 0.25f

/** How long the output has to be flat to count as the pulse dropping out. */
const val DROPOUT_MS = 1200L

/**
 * Watches the follower's output for flat stretches and names their cause.
 *
 * One flat stretch is one report, written when the stretch ends (or, for a stall, the moment
 * the late step arrives). Allocates only on a report.
 */
class DropoutWatch {
    class Report(
        val cause: DropoutCause,
        val quietMs: Long,
        val longestGapMs: Long,
        val pastMapMs: Long,
        val maxRaw: Float,
        val meanLoud: Float,
    )

    private var lastAt = -1L
    private var quietMs = 0L
    private var longestGap = 0L
    private var pastMs = 0L
    private var maxRaw = 0f
    private var loudSum = 0f
    private var loudN = 0

    /** Not stepping on purpose - paused, hidden or switched off. The next step starts fresh. */
    fun rest() {
        lastAt = -1L
        clear()
    }

    /**
     * One step of the follower at [now] (milliseconds, any monotonic clock). Returns a report
     * when a flat stretch of at least [DROPOUT_MS] has just ended, or a stall has just ended.
     */
    fun step(
        now: Long,
        pastMap: Boolean,
        raw: Float,
        loud: Float,
        blast: Float,
        swing: Float,
    ): Report? {
        val gap = if (lastAt < 0L) 0L else (now - lastAt).coerceAtLeast(0L)
        lastAt = now
        if (gap >= STALL_MS) {
            // The output could not have moved while nothing was stepping, so the stall IS the
            // drop-out, whatever the music was doing.
            val report = Report(DropoutCause.STALLED, quietMs + gap, gap, pastMs, maxRaw, meanLoud())
            clear()
            return report
        }
        val flat = blast < FLAT_BLAST && swing <= FLAT_SWING
        if (flat) {
            quietMs += gap
            if (gap > longestGap) longestGap = gap
            if (pastMap) pastMs += gap
            if (raw > maxRaw) maxRaw = raw
            loudSum += loud
            loudN++
            return null
        }
        if (quietMs < DROPOUT_MS) {
            clear()
            return null
        }
        val report = Report(
            classifyDropout(quietMs, longestGap, pastMs, maxRaw, meanLoud()),
            quietMs, longestGap, pastMs, maxRaw, meanLoud(),
        )
        clear()
        return report
    }

    private fun meanLoud(): Float = if (loudN == 0) 0f else loudSum / loudN

    private fun clear() {
        quietMs = 0L
        longestGap = 0L
        pastMs = 0L
        maxRaw = 0f
        loudSum = 0f
        loudN = 0
    }

    private companion object {
        /** Below this the wave is not visibly out. */
        const val FLAT_BLAST = 0.03f

        /** And at or below this the glow is not above its resting level. */
        const val FLAT_SWING = 0.02f
    }
}

/**
 * The beat as a SIGNED swing about what the track has been doing lately.
 *
 * ## Two ways, not one
 *
 * A light that only ever adds is half a pulse: it has to grow a long way before anything is
 * visible, and between beats it sits exactly where it sits with the pulse switched off, so
 * most of the time there is no evidence it is doing anything. Measured against the recent
 * mean, the glow falls BELOW its resting level in the gaps and rises above it on the hit - the
 * same total movement, seen twice as often, and the dark half is what makes the bright half
 * read as a punch rather than as a fade-up.
 *
 * ## The dip is gentler than the rise, and not for symmetry's sake
 *
 * [DIP] damps the negative half. Symmetrical, a quiet passage simply goes dark and stays
 * there, which reads as the artwork being dimmed rather than as the music being quiet. It also
 * biases the average swing very slightly positive, which is the right direction for the one
 * property this function exists to guarantee: the pulse must never leave the glow dimmer on
 * average than no pulse at all.
 *
 * @param hit the NORMALISED envelope now, 0 to 1 - see the gain control in
 *   [rememberBeatSignal]. Handing this a raw envelope works, and is how it shipped first, but
 *   it is then only as large as whatever range the envelope happened to have left.
 * @param mean what that has averaged lately, 0 to 1.
 */
fun beatSwing(hit: Float, mean: Float): Float {
    val raw = (hit - mean).coerceIn(-1f, 1f)
    return if (raw < 0f) raw * DIP else raw
}

/** How much of the below-average half is kept. */
const val DIP = 0.65f

/**
 * How quickly a beat fades, in milliseconds.
 *
 * Short, or two beats run together into one swell and the face breathes instead of beating.
 */
const val DECAY_MS = 220f

/**
 * How far back "lately" reaches, in milliseconds.
 *
 * Eight seconds, up from three. At three it was short enough to track the beat pattern ITSELF
 * rather than the track's character - so the average rose into the rhythm it was supposed to be
 * measured against, and the swing shrank away over the first few seconds of every song. The
 * normalising above is what makes a longer window safe: the range is restored before the
 * average is removed, so this only has to be slow enough not to chase the beat.
 */
const val MEAN_MS = 8000f

/**
 * How quickly the floor follows the envelope down, and how slowly it creeps back up.
 *
 * Asymmetric by a factor of twenty-four. The floor is meant to be where this track sits
 * BETWEEN hits, so it has to drop promptly into a quiet passage and must not be dragged up by
 * the hits themselves - a symmetrical follower would sit at the average, which is the thing the
 * mean already is.
 */
const val FLOOR_FALL_MS = 250f
const val FLOOR_RISE_MS = 6000f

/** And the ceiling, the other way round: quick to acknowledge a hard hit, slow to forget one. */
const val CEIL_RISE_MS = 120f
const val CEIL_FALL_MS = 4000f

/**
 * The least range the normalising will assume.
 *
 * Without a floor on the span, a passage where the envelope barely moves divides by almost
 * nothing and turns dither into a strobe. Twelve percent: below that there genuinely is no
 * dynamic range to show, and the honest answer is a still light.
 */
const val MIN_SPAN = 0.12f

/** And the loudness figure's own smoothing, which only has to stop it jittering. */
const val LOUD_MS = 260f

/**
 * How fast the wave falls back after a hit, in milliseconds - an exponential time constant.
 *
 * Bug identified here: the wave rose on a 40 ms linear chase and fell over 600 ms. A hit is one
 * 20 ms bucket, so the chase reached under half of the target before the hit had passed and the
 * peak landed late and low; the 600 ms tail then outlived the gap to the next beat at most
 * tempos, so consecutive hits merged into one long swell. That is the "weak wave" - it was
 * never allowed to be sharp. Now the rise is instant (the wave jumps to the hit on the step it
 * is read) and the fall is a 160 ms exponential: 95 percent gone in under half a second, so each
 * beat is its own burst with dark between them.
 */
const val WAVE_RELEASE_MS = 160f

/**
 * How much of the swing the wave takes.
 *
 * Above one on purpose. An ordinary hard beat swings about 0.55 above the mean, and the wave
 * should be at or near full for that rather than at half - the top of its range is for hits
 * that are genuinely bigger than the track's own normal, which is what the shape exists to
 * show.
 */
const val WAVE_GAIN = 1.7f

/** How far above its own normal a hit has to land to throw a ring outward. */
const val SHOCK_AT = 0.52f

/** And how long that ring takes to travel out and die, in milliseconds. */
const val SHOCK_MS = 520f

/**
 * How often the envelope is stepped.
 *
 * Sixty. An attack is 20ms, so anything slower steps over the moment the beat lands and
 * catches only its tail - the difference between a punch and a gentle swell. It is above the
 * glow's own 15fps on purpose: the drift is a forty-second ellipse and does not need the
 * frames, the beat does, and only this state ticks at this rate.
 *
 * This is the capable tier's rate. Below it the follower steps at 40 or 30 - see
 * `PlayerDials.pulseFps` for why 30 still cannot step over an onset.
 */
const val PULSE_FPS = 60L

/** The gain the sensitivity slider runs between. Below 1, only the hardest hits show. */
const val PULSE_SENSE_MIN = 0.4f
const val PULSE_SENSE_MAX = 3f

/**
 * The slider's 0-to-1 position as a gain, and back.
 *
 * Geometric, not linear. The useful range is 0.4 to 3, and on a linear track the default of 1
 * would sit a fifth of the way along with four fifths of the travel spent on gains nobody
 * wants - a slider whose interesting part is a thumb's width is not a control. On a geometric
 * one, halving and doubling are the same distance in either direction, which is how a gain is
 * actually heard.
 */
fun senseFor(position: Float): Float {
    val p = position.coerceIn(0f, 1f)
    val lo = kotlin.math.ln(PULSE_SENSE_MIN)
    val hi = kotlin.math.ln(PULSE_SENSE_MAX)
    return kotlin.math.exp(lo + (hi - lo) * p)
}

/** The inverse of [senseFor], for drawing the thumb where the stored gain is. */
fun sensePosition(gain: Float): Float {
    val lo = kotlin.math.ln(PULSE_SENSE_MIN)
    val hi = kotlin.math.ln(PULSE_SENSE_MAX)
    val g = gain.coerceIn(PULSE_SENSE_MIN, PULSE_SENSE_MAX)
    return ((kotlin.math.ln(g) - lo) / (hi - lo)).coerceIn(0f, 1f)
}

/**
 * The average swing over a run of envelope values, for the one property that must hold.
 *
 * A signed swing is easy to get the sign of wrong, and getting it wrong is not a visible bug -
 * it is a glow that is quietly a third dimmer than it should be, which is exactly the defect
 * this replaced and exactly the defect nobody spots by looking. So it is asserted instead of
 * eyeballed. See the test.
 */
internal fun averageSwing(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var mean = 0f
    var total = 0f
    for (s in samples) {
        mean += (s - mean) * 0.05f
        total += beatSwing(s, mean)
    }
    return total / samples.size
}

/** True when the swing is not quietly all dip. The guarantee, as a predicate. */
internal fun swingIsBalanced(samples: FloatArray): Boolean = abs(averageSwing(samples)) < 0.25f

/** How often the follower writes a line to the diagnostics, in milliseconds. */
const val LOG_EVERY_MS = 4000f

/**
 * Whether the follower's periodic line is built at all.
 *
 * `Diag.log` returns early in a release build, but the line handed to it was formatted first -
 * a `String.format` and a string concatenation every few seconds for a log nobody would see.
 */
private val DEBUG_LOG = dev.niccc2007.filet.BuildConfig.DEBUG
