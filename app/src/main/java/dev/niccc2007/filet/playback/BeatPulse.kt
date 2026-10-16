package dev.niccc2007.filet.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlin.math.exp
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * How hard the music is hitting, right now, as one number the whole screen can read.
 *
 * ## Why it is here and not inside whatever is drawing
 *
 * It started inside the ring's own draw lambda, which made the ring the only thing that could
 * see the beat - and the ring is a hairline around the artwork, a few percent of a face whose
 * background is the rest of it. Reported, correctly, as the glow barely pulsing at all: the
 * glow IS the face, and it was not being told. So the envelope is computed once, in one
 * place, and handed to everything that answers the music - the backdrop, the ring, and
 * whatever is added next.
 *
 * One [State] also means one clock. Three surfaces each running their own decay loop would
 * drift apart by a frame or two and the face would pulse slightly out of step with itself.
 *
 * ## The envelope, and why a beat is not a frame
 *
 * A hit is twenty milliseconds of audio. Drawn as it is, that is one frame of light, which
 * is a flicker rather than a pulse. So the peak is held and decays exponentially: a beat
 * reads as a punch with a tail, and two beats close together stack instead of cancelling.
 *
 * ## The position is dead-reckoned
 *
 * The player reports its position about five times a second. Sampling the beat map at those
 * is sampling every fourth beat at best. So the last reported position is stamped against
 * the wall clock and the gap is added - the track is playing at a known rate, and a few
 * hundred milliseconds of dead reckoning is far closer than the reading it sits between.
 */
@Composable
fun rememberBeatPulse(
    beats: BeatMap?,
    playback: Playback,
    on: Boolean,
    /** How readily a rise counts as a beat. See `Prefs.pulseSense`. */
    sensitivity: Float = 1f,
    /** How far ahead of the player the map is read, in milliseconds. See `Prefs.pulseLead`. */
    leadMs: Long = LEAD_MS,
): State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    // Kept across restarts of the loop below. A better map arrives every few hundred
    // milliseconds while a track is being read (see `BeatMap.build`), and a local would be
    // zeroed by each one - so the glow would drop out briefly, repeatedly, during exactly
    // the part of the track this was built to fix.
    val held = remember { floatArrayOf(0f) }
    val animated = rememberSystemAnimations()
    val live = on && animated && beats != null && !beats.empty
    LaunchedEffect(live, beats, sensitivity, leadMs) {
        if (!live) {
            held[0] = 0f
            level.floatValue = 0f
            return@LaunchedEffect
        }
        val map = beats!!
        var reported = -1L
        var stampedAt = android.os.SystemClock.elapsedRealtime()
        var at = stampedAt
        var value = held[0]
        while (true) {
            val state = playback.state.value
            val now = android.os.SystemClock.elapsedRealtime()
            // ASK THE PLAYER, and only fall back to the state flow when there is no answer.
            //
            // The flow is written five times a second, so reading it and extrapolating adds
            // up to two hundred milliseconds of the last tick's age to every reading. That
            // was never the whole of the lateness, but it was the part with no reason to
            // exist: the player knows where it is and answering costs one call.
            val livePos = playback.positionNow()
            if (livePos != null) {
                reported = livePos
                stampedAt = now
            } else if (state.positionMs != reported) {
                reported = state.positionMs
                stampedAt = now
            }
            // Clamped: a loop that was suspended while the screen was off comes back with a
            // gap of minutes, and exp(-huge) is zero - which is right - but the clamp also
            // keeps the arithmetic away from the edges.
            val gap = (now - at).coerceIn(0L, 250L).toFloat()
            at = now
            // LEAD_MS ahead of where the player says it is. Everything between the
            // decoder and the ear is latency the player does not report - the mixer, the
            // device's own buffer, a speaker DSP - and all of it makes a light driven by
            // the reported position land behind the sound.
            val at = reported + (now - stampedAt) + leadMs
            val raw =
                if (!state.playing) 0f
                else (map.strengthAt(at) * sensitivity).coerceIn(0f, 1f)
            value = max(value * exp(-gap / DECAY_MS), raw)
            held[0] = value
            level.floatValue = value
            delay(1000L / PULSE_FPS)
        }
    }
    return level
}

/**
 * How quickly a beat fades, in milliseconds.
 *
 * Short, or two beats run together into one swell and the face breathes instead of beating.
 * Longer than it was (150ms): at that length the bloom was gone before the eye had found it,
 * which is half of why the glow looked like it was not reacting.
 */
const val DECAY_MS = 220f

/**
 * How often the envelope is stepped.
 *
 * Forty-five. An attack is 20ms, so anything slower steps over the moment the beat lands and
 * catches only its tail - the difference between a punch and a gentle swell. It is above the
 * glow's own 15fps on purpose: the drift is a forty-second ellipse and does not need the
 * frames, the beat does, and only this state ticks at this rate.
 */
const val PULSE_FPS = 60L

/**
 * How far ahead of the reported position the beat map is read.
 *
 * The DEFAULT, and the reason there is a slider for it.
 *
 * Android reports where the decoder is. What reaches the speaker is behind that by the
 * mixer's buffer plus whatever the device adds on top - its own effects, a speaker DSP, a
 * Bluetooth hop - and none of that is reportable. It is commonly forty to a hundred and
 * fifty milliseconds and it is different on every phone, so no constant compiled in here can
 * be right for all of them, and sixty was plainly not right for this one.
 *
 * A hundred and forty as the default, and a control to move it. A pulse a hair early reads
 * as anticipation and a hair late reads as broken, so the error is deliberately biased
 * toward early.
 */
const val LEAD_MS = 140L

/** What the timing slider can ask for, in milliseconds ahead of the reported position. */
const val LEAD_MIN = -60L
const val LEAD_MAX = 400L

/** The slider's 0-to-1 position as a lead, and back. Linear: milliseconds are milliseconds. */
fun leadFor(position: Float): Long =
    (LEAD_MIN + (LEAD_MAX - LEAD_MIN) * position.coerceIn(0f, 1f)).toLong()

/** The inverse of [leadFor]. */
fun leadPosition(ms: Long): Float =
    ((ms.coerceIn(LEAD_MIN, LEAD_MAX) - LEAD_MIN).toFloat() / (LEAD_MAX - LEAD_MIN))

/** The gain the sensitivity slider runs between. Below 1, only the hardest hits show. */
const val PULSE_SENSE_MIN = 0.4f
const val PULSE_SENSE_MAX = 3f

/**
 * The slider's 0-to-1 position as a gain, and back.
 *
 * Geometric, not linear. The useful range is 0.4 to 3, and on a linear track the default of
 * 1 would sit a fifth of the way along with four fifths of the travel spent on gains nobody
 * wants - a slider whose interesting part is a thumb's width is not a control. On a
 * geometric one, halving and doubling are the same distance in either direction, which is
 * how a gain is actually heard.
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
