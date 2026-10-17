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
 * @param blast the wave's amplitude: fast to rise, slow to fall, and zero through anything
 *   that is not a rise. See [WAVE_ATTACK_MS].
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
 */
@Composable
fun rememberBeatSignal(
    beats: BeatMap?,
    playback: Playback,
    on: Boolean,
    /** How readily a rise counts as a beat. See `Prefs.pulseSense`. */
    sensitivity: Float = 1f,
): BeatSignal {
    val level = remember { mutableFloatStateOf(0f) }
    val swing = remember { mutableFloatStateOf(0f) }
    val blast = remember { mutableFloatStateOf(0f) }
    val shock = remember { mutableFloatStateOf(0f) }
    val loud = remember { mutableFloatStateOf(0f) }
    val signal = remember { BeatSignal(level, swing, blast, shock, loud) }

    // Kept across restarts of the loop below. A better map arrives every few hundred
    // milliseconds while a track is being read (see `BeatMap.from`), and locals would be
    // zeroed by each one - so the glow would drop out briefly, repeatedly, during exactly the
    // part of the track this was built to fix.
    val held = remember { FloatArray(5) }
    val animated = rememberSystemAnimations()
    val context = LocalContext.current
    val live = on && animated && beats != null && !beats.empty

    LaunchedEffect(live, beats, sensitivity) {
        if (!live) {
            held.fill(0f)
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
        var reported = -1L
        var stampedAt = android.os.SystemClock.elapsedRealtime()
        var at = stampedAt
        var value = held[0]
        var mean = held[1]
        var wave = held[2]
        var impulse = held[3]
        var loudness = held[4]
        var wasOver = false
        while (true) {
            val state = playback.state.value
            val now = android.os.SystemClock.elapsedRealtime()
            // ASK THE PLAYER, and only fall back to the flow when there is no answer. The
            // flow is written five times a second, so reading it adds up to two hundred
            // milliseconds of the last tick's age to every reading.
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
            val raw =
                if (!state.playing) 0f
                else (map.strengthAt(ear) * sensitivity).coerceIn(0f, 1f)

            // ── the punch: instant up, exponential down ──
            value = max(value * exp(-gap / DECAY_MS), raw)

            // ── what this track has been doing lately ──
            //
            // The resting point used to be the constant 0.34, and that was the whole of the
            // glow going missing: this envelope decays to ZERO between beats, so every moment
            // that was not a drum measured as 0.34 BELOW rest and the lights were multiplied
            // by about two thirds. Switching the pulse on therefore made the glow dimmer on
            // average than switching it off, which is the opposite of what a pulse is.
            //
            // Measured against the track's own mean instead, the swing averages about zero by
            // construction, so the pulse can only move the glow around where it already was -
            // never move it down and leave it there. It also self-calibrates: a sparse ambient
            // track and a wall of drums each sit at their own normal.
            mean += (value - mean) * (gap / MEAN_MS).coerceIn(0f, 1f)
            val signed = beatSwing(value, mean)

            // ── the wave: fast to rise, slow to fall, flat when nothing is happening ──
            //
            // Driven by the SIGNED swing rather than the envelope, which is what makes a calm
            // passage genuinely flat instead of gently rippling: through anything that is not
            // a rise above this track's own normal, the target is zero.
            val want = (max(0f, signed) * WAVE_GAIN).coerceIn(0f, 1f)
            val k = if (want > wave) gap / WAVE_ATTACK_MS else gap / WAVE_RELEASE_MS
            wave += (want - wave) * k.coerceIn(0f, 1f)

            // ── the impulse: only the big ones, and only once each ──
            val over = signed > SHOCK_AT
            impulse = if (over && !wasOver) 1f else max(0f, impulse - gap / SHOCK_MS)
            wasOver = over

            // ── the figure: loudness, smoothed, which is a shape and not a hit ──
            val nowLoud = if (!state.playing) 0f else map.levelAt(ear)
            loudness += (nowLoud - loudness) * (gap / LOUD_MS).coerceIn(0f, 1f)

            held[0] = value
            held[1] = mean
            held[2] = wave
            held[3] = impulse
            held[4] = loudness
            level.floatValue = value
            swing.floatValue = signed
            blast.floatValue = wave
            shock.floatValue = impulse
            loud.floatValue = loudness
            delay(1000L / PULSE_FPS)
        }
    }
    return signal
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
 * @param hit the envelope now, 0 to 1.
 * @param mean what it has averaged lately, 0 to 1.
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
 * Three seconds. Long enough that it is the track's character rather than the last bar, short
 * enough to follow a song into its chorus and back out - a thirty-second window would spend
 * the first chorus catching up and the verse after it over-reacting.
 */
const val MEAN_MS = 3000f

/** And the loudness figure's own smoothing, which only has to stop it jittering. */
const val LOUD_MS = 260f

/**
 * How fast the wave arrives, in milliseconds.
 *
 * Forty. An attack is twenty, so this is as fast as it can be without being a step change -
 * and the asymmetry against [WAVE_RELEASE_MS] is the whole effect. Equal times give a ripple
 * that is always half way through something; fifteen times slower coming down gives a wave
 * that snaps out and then unwinds.
 */
const val WAVE_ATTACK_MS = 40f

/** And how slowly it unwinds. */
const val WAVE_RELEASE_MS = 600f

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
