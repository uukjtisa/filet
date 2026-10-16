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
fun rememberBeatPulse(beats: BeatMap?, playback: Playback, on: Boolean): State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    val animated = rememberSystemAnimations()
    val live = on && animated && beats != null && !beats.empty
    LaunchedEffect(live, beats) {
        if (!live) {
            level.floatValue = 0f
            return@LaunchedEffect
        }
        val map = beats!!
        var reported = -1L
        var stampedAt = android.os.SystemClock.elapsedRealtime()
        var at = stampedAt
        var value = 0f
        while (true) {
            val state = playback.state.value
            val now = android.os.SystemClock.elapsedRealtime()
            if (state.positionMs != reported) {
                reported = state.positionMs
                stampedAt = now
            }
            // Clamped: a loop that was suspended while the screen was off comes back with a
            // gap of minutes, and exp(-huge) is zero - which is right - but the clamp also
            // keeps the arithmetic away from the edges.
            val gap = (now - at).coerceIn(0L, 250L).toFloat()
            at = now
            val raw = if (!state.playing) 0f else map.strengthAt(reported + (now - stampedAt))
            value = max(value * exp(-gap / DECAY_MS), raw)
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
const val PULSE_FPS = 45L
