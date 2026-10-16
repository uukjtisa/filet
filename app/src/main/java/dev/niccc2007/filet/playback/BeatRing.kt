package dev.niccc2007.filet.playback

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
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
 * The envelope is not computed here any more - see [rememberBeatPulse]. It was, and that made
 * the ring the only thing on the face that could see the beat, which is why the glow behind
 * it appeared not to react at all.
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
    /** The shared envelope. Null when the pulse is off, and the ring then sweeps on a clock. */
    pulse: androidx.compose.runtime.State<Float>? = null,
    /** The track's onsets, for the wave. Null when the pulse is off. */
    beats: BeatMap? = null,
    /** The same lead the envelope uses, so the wave and the pulse agree about now. */
    leadMs: Long = LEAD_MS,
    /** How far the wave sits off the artwork, 0 to 1. See `Prefs.waveGap`. */
    waveGap: Float = 0.35f,
    /** How tall the wave's peaks are, 0 to 1. Zero draws no wave at all. */
    waveSize: Float = 0.5f,
    /** How far a beat may move it. See `Prefs.pulseDepth`. */
    pulseDepth: Float = 1f,
    /**
     * Whether the living glow is on.
     *
     * It was not asked, and this is a light: a ring of colour plus a radial halo thrown
     * outward from the artwork. So turning the glow off removed the background wash and left
     * a second glow sitting round the disc with nothing governing it - reported exactly that
     * way, and fair. Off, what remains is the hairline the artwork needs to have an edge.
     */
    glow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state = playback.state.collectAsStateWithLifecycle()
    val running = state.value.playing
    val sweep = rememberPhase(SWEEP_MS, running)
    val breath = rememberPhase(BREATH_MS, running)

    Canvas(modifier) {
        // Read in the draw lambda. See `livingGlow` for what reading it above costs. The
        // pulse goes the same way, and reading it here is also what invalidates the draw on
        // every beat without recomposing anything.
        val s = sweep.value
        val b = breath.value
        val r = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val swell = sin(b * 2f * PI.toFloat())
        val onBeat = pulse != null
        // The ring takes the positive half of the swing only. It is a hairline on the edge
        // of the artwork; dimming it further in the gaps would simply remove it, where the
        // backdrop has the area to lose brightness and still be seen losing it.
        val hit =
            if (!running || pulse == null) 0f
            else glowSwing(pulse.value, pulseDepth).coerceAtLeast(0f) * 1.5f
        val lit = (0.25f + 0.75f * turning) * (1f + hit * PUNCH)

        // The ring the artwork sits inside, just off its edge so the picture is not touched.
        val ring = r * (1.012f + 0.010f * swell + RING_KICK * hit)
        drawCircle(
            color = tint.copy(alpha = (0.16f + 0.08f * swell) * lit),
            radius = ring,
            center = centre,
            style = Stroke(width = r * (0.018f + 0.006f * swell + 0.020f * hit)),
        )
        // Everything past here is light rather than edge. With the glow off, the disc keeps
        // its rim and nothing else.
        if (!glow) return@Canvas

        // ── the wave ───────────────────────────────────────────────────────────────
        //
        // The travelling highlight that used to live here was a clock with a gradient on
        // it: a bright arc going round at a fixed rate, which looks like a loading spinner
        // the moment you notice the rate never changes. This is the track's own shape.
        //
        // The last [WAVE_HISTORY_MS] of onsets, wrapped round the circle with NOW at the
        // top. Each beat therefore enters at twelve o'clock as it is heard and travels
        // round as it ages, so the ring is a rolling picture of the few seconds just
        // played rather than a decoration keeping time with nothing. It needs no new
        // analysis - the map is already read for the pulse, and sampling it is an array
        // index per point.
        if (onBeat && waveSize > 0f && beats != null && !beats.empty) {
            val nowMs = (playback.positionNow() ?: state.value.positionMs) + leadMs
            val base = ring * (1f + WAVE_OFF_MIN + waveGap * WAVE_OFF_SPAN)
            val amp = r * (WAVE_AMP_MIN + waveSize * WAVE_AMP_SPAN) * (1f + hit * 0.5f)
            // Smoothed over three samples. A 20ms bucket next to a silent one is a spike,
            // and a ring of spikes reads as noise; three is enough to make a peak a shape
            // without blunting the attack that makes it worth drawing.
            // The LOUDNESS for the figure and the onsets for the punch on top of it. On
            // onsets alone the ring is a circle with occasional dents, because that is what
            // a derivative looks like; the envelope is what gives it a shape at every angle.
            fun at(i: Int): Float {
                val t = i.toFloat() / WAVE_POINTS
                val ms = nowMs - (t * WAVE_HISTORY_MS).toLong()
                return (beats.levelAt(ms) * 0.8f + beats.strengthAt(ms) * 0.5f)
                    .coerceAtMost(1.2f)
            }
            val path = Path()
            for (i in 0..WAVE_POINTS) {
                val v = (at(i - 1) + at(i) * 2f + at(i + 1)) / 4f
                val a = i.toFloat() / WAVE_POINTS * 2f * PI.toFloat() - PI.toFloat() / 2f
                val rad = base + amp * v
                val x = centre.x + kotlin.math.cos(a) * rad
                val y = centre.y + kotlin.math.sin(a) * rad
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            // Brightest where the newest beat is and fading round to the oldest, so the
            // whole figure reads as moving even in a still frame.
            val head = tint.copy(alpha = 0.85f * lit)
            val tail = tint.copy(alpha = 0.10f * lit)
            val brush = Brush.sweepGradient(
                0.00f to head,
                0.35f to tint.copy(alpha = 0.45f * lit),
                0.75f to tail,
                1.00f to head,
                center = centre,
            )
            // Twice: a wide, faint pass for the bloom and a fine one for the line itself.
            drawPath(
                path, brush,
                style = Stroke(width = r * 0.030f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.22f,
            )
            drawPath(
                path, brush,
                style = Stroke(width = r * 0.010f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // The glow it throws outward. A radial that starts AT the ring, so the artwork keeps
        // its own edge and the light only ever goes away from it.
        // And it reaches further on a beat, so the light moves outward rather than only
        // getting brighter - a change of size is seen from across a room, a change of alpha
        // is not.
        val reach = r * (1.34f + 0.16f * hit)
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

/**
 * How hard a beat lifts the ring, on top of whatever it was already doing.
 *
 * Was 1.6, which on a ring already at a sixth of an alpha was a change nobody saw across the
 * room. The complaint was that the face barely moved, and most of the answer is here and in
 * [RING_KICK]: brightness alone is not noticed, SIZE is.
 */
private const val PUNCH = 2.8f

/** How far the ring jumps outward on a hit, as a fraction of the artwork's radius. */
private const val RING_KICK = 0.075f

/**
 * How much of the travelling highlight is left once the beats are driving the ring.
 *
 * None. It was damped to a fifth and left running, and a bright arc going round on its own
 * 2.4-second clock next to a light answering the drums is a second rhythm in the picture -
 * reported as something starting before the beat does, which is precisely what it was. The
 * sweep is the FALLBACK for a track with no map; with a map it has nothing to add and one
 * clear thing to get in the way of.
 */
private const val SWEEP_UNDER_BEAT = 0f

/** How many points go round the wave. Every two degrees: smooth at this size, and cheap. */
private const val WAVE_POINTS = 180

/**
 * How much of the recent past the wave holds.
 *
 * Three seconds, which at ordinary tempos is six to eight beats - enough that the figure
 * has a rhythm in it rather than one lump, and short enough that what is on screen is
 * recognisably what was just heard.
 */
private const val WAVE_HISTORY_MS = 3_000f

/** Where the wave sits, as a fraction of the ring, at the two ends of its control. */
private const val WAVE_OFF_MIN = 0.02f
private const val WAVE_OFF_SPAN = 0.26f

/** And how tall its peaks are, as a fraction of the artwork's radius. */
private const val WAVE_AMP_MIN = 0.02f
private const val WAVE_AMP_SPAN = 0.22f

/** How long the highlight takes to go round. Inside the range most music sits in. */
private const val SWEEP_MS = 2_400

/** And the slower swell underneath it. Not a multiple of the sweep, so they never line up. */
private const val BREATH_MS = 5_600
