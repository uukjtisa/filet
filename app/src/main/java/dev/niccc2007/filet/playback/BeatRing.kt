package dev.niccc2007.filet.playback

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.sin

/**
 * The halo around Aurora's disc: a ring of light with a brighter wave travelling round it.
 *
 * ## What this replaced, and why
 *
 * A full vinyl - black rim, grooves, lead-in, paper label - was built here and reverted. It
 * was a faithful record and it cost the artwork more than half of its radius, on the one face
 * whose entire subject is the artwork. The disc is said with light instead: the picture stays
 * the size it was, the spindle stays small and dim, and what makes it read as a record is a
 * ring that turns with it.
 *
 * ## With a beat map it IS following the music
 *
 * When the pulse is switched on, [BeatMap] has read the track and knows where its onsets are,
 * and the ring punches on them instead of sweeping on a clock. Without one it falls back to
 * the travelling highlight described below, which is honest about being a clock.
 *
 * ## Without one, it is not listening to the music, and that is deliberate
 *
 * A ring that genuinely followed the beat would need the audio samples, and on Android the
 * only way to those is `android.media.audiofx.Visualizer`, which requires the RECORD_AUDIO
 * permission - a microphone prompt, in a file manager, for a decoration. That trade is not
 * worth making and would frighten people who are right to be frightened by it.
 *
 * So the wave is on its own clock, and it is built to feel like a beat rather than to look
 * like a timer:
 *
 * - the sweep goes round once every [SWEEP_MS], which at 2.4 seconds is inside the range most
 *   music sits in, so it reads as being in time with something
 * - the ring BREATHES on a second, slower period that does not divide into the first, so the
 *   two drift against each other and no two turns are quite the same
 * - the wave is a narrow bright arc on a dim ring rather than the whole ring pulsing, because
 *   a pulse on a timer is obviously a timer and a travelling highlight is not
 *
 * Honest framing in the release notes too. A claim that it reacts to the music would be found
 * out by the first person who paused mid-track.
 */
@Composable
fun BeatRing(
    tint: Color,
    playback: Playback,
    turning: Float,
    beats: BeatMap? = null,
    modifier: Modifier = Modifier,
) {
    val state = playback.state.collectAsStateWithLifecycle()
    val running = state.value.playing
    val sweep = rememberPhase(SWEEP_MS, running)
    val breath = rememberPhase(BREATH_MS, running)
    // A clock for the beat, fast enough to catch one. The position in the player's state is
    // five readings a second, which steps straight over an attack, so the position is
    // EXTRAPOLATED between them - the player is playing at a known rate, and a few hundred
    // milliseconds of dead reckoning is more accurate here than the sample it is between.
    val tick = rememberPhase(TICK_MS, running && beats != null, fps = 60L)
    val fromState = state.value.positionMs
    val stamped = remember(fromState) { android.os.SystemClock.elapsedRealtime() }
    // The envelope: a hit is 20ms of audio and would be one frame of light, which is not a
    // pulse, it is a flicker. The peak is held and then decays, so a beat reads as a punch
    // with a tail. Plain arrays rather than state - this is read and written inside the draw
    // and must not invalidate anything.
    val envelope = remember { floatArrayOf(0f) }
    // The clock is a Long on purpose: elapsedRealtime is hundreds of millions of
    // milliseconds on a phone that has been up a few days, and a Float holds about seven
    // digits - the difference between two frames would round away to zero and the beat would
    // never decay.
    val envelopeAt = remember { longArrayOf(0L) }

    Canvas(modifier) {
        // Read in the draw lambda. See `livingGlow` for what reading it above costs.
        val s = sweep.value
        val b = breath.value
        val r = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val swell = sin(b * 2f * PI.toFloat())
        // Read so the draw invalidates on it; the value itself is only a clock.
        @Suppress("UNUSED_VARIABLE") val beatTick = tick.value
        val onBeat = beats != null && !beats.empty
        val clockNow = android.os.SystemClock.elapsedRealtime()
        val raw = if (!onBeat || !running) {
            0f
        } else {
            beats!!.strengthAt(fromState + (clockNow - stamped))
        }
        val gap = (clockNow - envelopeAt[0]).coerceIn(0L, 200L).toFloat()
        envelopeAt[0] = clockNow
        val decayed = envelope[0] * kotlin.math.exp(-gap / DECAY_MS)
        val hit = kotlin.math.max(decayed, raw)
        envelope[0] = hit
        val lit = (0.25f + 0.75f * turning) * (1f + hit * PUNCH)

        // The ring the artwork sits inside, just off its edge so the picture is not touched.
        val ring = r * (1.012f + 0.010f * swell + 0.022f * hit)
        drawCircle(
            color = tint.copy(alpha = (0.16f + 0.08f * swell) * lit),
            radius = ring,
            center = centre,
            style = Stroke(width = r * (0.018f + 0.006f * swell)),
        )

        // The glow it throws outward. A radial that starts AT the ring, so the artwork keeps
        // its own edge and the light only ever goes away from it.
        val reach = r * 1.34f
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                (ring / reach) to tint.copy(alpha = (0.22f + 0.10f * swell) * lit),
                1f to Color.Transparent,
                center = centre,
                radius = reach,
            ),
            radius = reach,
            center = centre,
        )

        // The wave: a bright arc travelling round, drawn with a sweep gradient rotated by the
        // phase. Narrow, so it reads as a highlight moving over the ring rather than as the
        // ring changing colour.
        // The travelling highlight is the fallback, not the feature. With a beat map it is
        // damped right down: it was what made this read as a slow pulse when the whole point
        // was to answer the drums.
        val sweepAlpha = if (onBeat) SWEEP_UNDER_BEAT else 1f
        rotate(degrees = s * 360f, pivot = centre) {
            drawArc(
                brush = Brush.sweepGradient(
                    0.00f to Color.Transparent,
                    0.06f to tint.copy(alpha = 0.55f * lit * sweepAlpha),
                    0.11f to Color.White.copy(alpha = 0.30f * lit * sweepAlpha),
                    0.16f to tint.copy(alpha = 0.55f * lit * sweepAlpha),
                    0.26f to Color.Transparent,
                    1.00f to Color.Transparent,
                    center = centre,
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(centre.x - ring, centre.y - ring),
                size = Size(ring * 2f, ring * 2f),
                style = Stroke(width = r * 0.034f),
            )
        }
    }
}

/** How hard a beat lifts the ring, on top of whatever it was already doing. */
private const val PUNCH = 1.6f

/** How quickly a beat fades, in milliseconds. Short, or two beats become one swell. */
private const val DECAY_MS = 150f

/** How much of the travelling highlight is left once the beats are driving the ring. */
private const val SWEEP_UNDER_BEAT = 0.22f

/** The clock the beat is sampled on. Short, because an attack is shorter. */
private const val TICK_MS = 1_000

/** How long the highlight takes to go round. Inside the range most music sits in. */
private const val SWEEP_MS = 2_400

/** And the slower swell underneath it. Not a multiple of the sweep, so they never line up. */
private const val BREATH_MS = 5_600
