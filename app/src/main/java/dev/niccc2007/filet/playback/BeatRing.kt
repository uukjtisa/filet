package dev.niccc2007.filet.playback

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The halo around Aurora's disc: a ring of light with a wave that answers the drums.
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
 * The envelope is not computed here - see [rememberBeatSignal]. It was, once, and that made
 * the ring the only thing on the face that could see the beat, which is why the glow behind it
 * appeared not to react at all.
 *
 * ## Without one, it is not listening to the music, and that is deliberate
 *
 * A ring that genuinely followed the beat would need the audio samples, and on Android the
 * only way to those is `android.media.audiofx.Visualizer`, which requires the RECORD_AUDIO
 * permission - a microphone prompt, in a file manager, for a decoration. That trade is not
 * worth making and would frighten people who are right to be frightened by it.
 *
 * So the fallback wave is on its own clock, and it is built to feel like a beat rather than to
 * look like a timer: the sweep goes round once every [SWEEP_MS], which is inside the range
 * most music sits in; the ring breathes on a second, slower period that does not divide into
 * the first; and the highlight is a narrow bright arc rather than the whole ring pulsing,
 * because a pulse on a timer is obviously a timer and a travelling highlight is not.
 */
@Composable
fun BeatRing(
    tint: Color,
    playback: Playback,
    turning: Float,
    /** The beat, or null when the pulse is off - the ring then sweeps on a clock. */
    signal: BeatSignal? = null,
    /** How far the wave sits off the artwork, 0 to 1. See `Prefs.waveGap`. */
    waveGap: Float = 0.35f,
    /** How deep the lobes get on a full-strength beat, 0 to 1. Zero draws no wave. */
    waveSize: Float = 0.5f,
    /** And how deep they are with nothing happening. Zero is a flat circle at rest. */
    waveRest: Float = 0.12f,
    /** How far a beat may move it. See `Prefs.pulseDepth`. */
    pulseDepth: Float = 1f,
    /**
     * Whether the living glow is on.
     *
     * It was not asked, and this is a light: a ring of colour plus a radial halo thrown
     * outward from the artwork. So turning the glow off removed the background wash and left a
     * second glow sitting round the disc with nothing governing it. Off, what remains is the
     * hairline the artwork needs in order to have an edge.
     */
    glow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state = playback.state.collectAsStateWithLifecycle()
    val running = state.value.playing
    val sweep = rememberPhase(SWEEP_MS, running)
    val breath = rememberPhase(BREATH_MS, running)

    Canvas(modifier) {
        // Read in the draw lambda. See `livingGlow` for what reading it above costs. The beat
        // goes the same way, and reading it here is also what invalidates the draw on every
        // beat without recomposing anything.
        val s = sweep.value
        val b = breath.value
        val r = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val swell = sin(b * 2f * PI.toFloat())
        val onBeat = signal != null
        // The ring takes the positive half of the swing only. It is a hairline on the edge of
        // the artwork; dimming it further in the gaps would simply remove it, where the
        // backdrop has the area to lose brightness and still be seen losing it.
        val hit =
            if (!running || signal == null) 0f
            else (signal.swing.value * pulseDepth).coerceAtLeast(0f) * 1.5f
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

        // ── the wave ───────────────────────────────────────────────────────────────────
        //
        // ## Three versions this is not
        //
        // It was a bright arc going round at a fixed rate - a clock with a gradient on it,
        // which reads as a loading spinner the moment you notice the rate never changes.
        //
        // The second drew the last three seconds of the track wrapped round the circle.
        // Honest, and still wrong: the shape is fixed and takes three seconds to come round,
        // so nothing appears to move and a beat only made the whole ring bigger. A wave that
        // expands is not waving.
        //
        // The third was this standing wave driven by an envelope that rose as slowly as it
        // fell. The shape moved, which was the fix, but it moved CONSTANTLY and at much the
        // same depth whatever the music did - a permanent gentle ripple, which is a decoration
        // and not a reaction.
        //
        // ## What makes this one answer the music
        //
        // Three things, and the first is the one that matters:
        //
        //  1. **The drive is asymmetric.** [WAVE_ATTACK_MS] up, fifteen times that down. A
        //     wave that takes as long to arrive as to leave is always half way through
        //     something; one that snaps out and then unwinds has a moment of arrival, and the
        //     moment of arrival is the entire effect.
        //  2. **The drive is RELATIVE.** It comes from the signed swing about this track's own
        //     recent average, so a calm passage genuinely sits at rest rather than rippling
        //     along at whatever its mastering happens to be. Flat means flat.
        //  3. **A harder hit adds harmonics, not just depth.** Depth alone inflates the figure.
        //     [LOBES] fade IN with the drive, so a soft beat is two lazy swells and a hard one
        //     is eight tight ones - the difference between a bigger wobble and something
        //     breaking.
        //
        // And on the rare hit that lands well above the track's own ceiling, one ring of
        // displacement is thrown outward and dies. See [SHOCK_AT] - it fires seldom on purpose,
        // because a shock on every beat is not a shock.
        if (onBeat && waveSize > 0f) {
            val seconds = android.os.SystemClock.elapsedRealtime() / 1000.0
            val base = ring * (1f + WAVE_OFF_MIN + waveGap * WAVE_OFF_SPAN)
            // BETWEEN two settings rather than scaled by one.
            //
            // There was a single size with an idle built into it, so the wave rippled away
            // whatever the music did. Rest is where it sits with nothing happening, size is
            // where a full-strength beat takes it, and the music moves it between them. At
            // rest zero it is a plain circle until a drum arrives.
            val drive = (signal!!.blast.value * pulseDepth).coerceIn(0f, 1f)
            // The resting depth leans on the LOUDNESS rather than being a flat idle, so a
            // quiet intro is flatter than a loud verse even before a drum lands in either.
            val rest = waveRest.coerceIn(0f, 1f) * (REST_FLOOR + REST_LOUD * signal.loud.value)
            val amp = r * WAVE_AMP_SPAN * (rest + (waveSize - rest).coerceAtLeast(0f) * drive)
            // How much of the detail is in circuit. The two lowest harmonics carry the swell
            // at any drive; the rest arrive as it climbs, each later than the last.
            val path = Path()
            for (i in 0..WAVE_POINTS) {
                val th = i.toFloat() / WAVE_POINTS * 2f * PI.toFloat()
                var d = 0f
                var peak = 0f
                for (k in LOBES.indices) {
                    val lobe = LOBES[k]
                    val w = lobe.weight * lobe.entersAt(drive)
                    if (w <= 0f) continue
                    peak += w
                    d += w * sin(lobe.count * th + (lobe.speed * seconds).toFloat())
                }
                // Against the TYPICAL peak, not the sum of the weights.
                //
                // Harmonics at unrelated phases almost never line up, so dividing by the sum -
                // the one arrangement where they all peak together - scaled the whole figure
                // down by about three and the ring came out looking like a circle. Normalising
                // against what it actually reaches, and clamping the rare alignment, is the
                // difference between a wave and a hairline. Summed over the lobes that are in
                // circuit rather than over all of them, or fading one in would quietly shrink
                // every other one.
                val norm = (peak * LOBE_PEAK).coerceAtLeast(0.0001f)
                val rad = base + amp * (d / norm).coerceIn(-1.25f, 1.25f)
                val a = th - PI.toFloat() / 2f
                val x = centre.x + kotlin.math.cos(a) * rad
                val y = centre.y + kotlin.math.sin(a) * rad
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val head = tint.copy(alpha = 0.85f * lit)
            val brush = Brush.sweepGradient(
                0.00f to head,
                0.35f to tint.copy(alpha = 0.45f * lit),
                0.75f to tint.copy(alpha = 0.14f * lit),
                1.00f to head,
                center = centre,
            )
            // Twice: a wide faint pass for the bloom and a fine one for the line itself. The
            // same two-pass stroke the tree's guides and the active frame use, which is what
            // keeps this reading as part of this app rather than as a visualiser dropped into
            // it - a hairline with light behind it, in the artwork's own colour, and no neon.
            drawPath(
                path, brush,
                style = Stroke(width = r * 0.034f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                alpha = 0.20f,
            )
            drawPath(
                path, brush,
                style = Stroke(width = r * 0.009f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // ── the shock ──────────────────────────────────────────────────────────────
            //
            // One ring, outward, on the hits that are genuinely bigger than this track's
            // normal. It travels as it dies, so it is drawn from the impulse's REMAINING
            // value: 1 is the moment it fired and sitting on the wave, 0 is gone and out at
            // [SHOCK_REACH].
            val impulse = signal.shock.value
            if (impulse > 0f) {
                val travel = 1f - impulse
                val at = base * (1f + SHOCK_REACH * travel)
                // Fading as the square, so it is bright for the first instant and then mostly
                // gone - a linear fade reads as a ring being dragged outward by hand.
                val fade = impulse * impulse
                drawCircle(
                    color = tint.copy(alpha = 0.40f * fade * lit),
                    radius = at,
                    center = centre,
                    style = Stroke(width = r * (0.004f + 0.012f * impulse)),
                )
                drawCircle(
                    color = tint.copy(alpha = 0.10f * fade * lit),
                    radius = at,
                    center = centre,
                    style = Stroke(width = r * 0.05f),
                )
            }
        }

        // The travelling highlight is the FALLBACK, not the feature. With a beat map it is off
        // entirely: a bright arc going round on its own 2.4-second clock, next to a light
        // answering the drums, is a second rhythm in the picture - which presented as
        // something starting before the beat did, because that is exactly what it was.
        val sweepAlpha = if (onBeat) SWEEP_UNDER_BEAT else 1f
        if (sweepAlpha > 0f) {
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
 * None. It was damped to a fifth and left running, and that was a second clock in the picture.
 * The sweep is the fallback for a track with no map; with a map it has nothing to add and one
 * clear thing to get in the way of.
 */
private const val SWEEP_UNDER_BEAT = 0f

/** How many points go round the wave. Every two degrees: smooth at this size, and cheap. */
private const val WAVE_POINTS = 180

/**
 * One travelling lobe pattern.
 *
 * @param count how many bumps it makes round the circle.
 * @param speed how fast it turns, in radians a second. Negative turns the other way.
 * @param weight how much of the final shape is this one, once it is in circuit.
 * @param from the drive at which it starts to arrive, 0 to 1.
 * @param to and where it is fully in.
 */
private class Lobe(
    val count: Int,
    val speed: Double,
    val weight: Float,
    val from: Float = 0f,
    val to: Float = 0f,
) {
    /**
     * How much of this harmonic is in circuit at this drive.
     *
     * Smoothstepped rather than linear: a harmonic that fades in linearly announces itself
     * arriving, and what should be visible is the figure getting busier, not one ripple being
     * switched on.
     */
    fun entersAt(drive: Float): Float {
        if (to <= from) return 1f
        val t = ((drive - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * The harmonics the wave is made of, and when each one arrives.
 *
 * Counts that share no factor and speeds that are not multiples of each other, so the pattern
 * they add up to never comes back round to where it started; two turn against the other two,
 * which is what makes lobes appear to curl into one another rather than march.
 *
 * The weights fall off with count because an eight-lobed ripple at the same depth as a
 * two-lobed one reads as noise. The ARRIVAL thresholds are the part that answers the music: at
 * rest only the two slow swells are in circuit, so a calm passage is a soft oval breathing;
 * by full drive all five are, and the figure is tight and busy and clearly being hit. Depth
 * alone could never do that - a deeper two-lobed wave is just a bigger oval.
 */
private val LOBES = arrayOf(
    Lobe(2, 0.55, 1.00f),
    Lobe(3, -0.90, 0.70f),
    Lobe(5, 1.35, 0.42f, from = 0.18f, to = 0.55f),
    Lobe(8, -2.10, 0.24f, from = 0.40f, to = 0.80f),
    Lobe(13, 3.10, 0.14f, from = 0.68f, to = 1.00f),
)

/**
 * What the harmonics typically add up to at their peaks, as a fraction of their total weight.
 *
 * Just over half. Several waves at unrelated phases reach all of their maxima at the same
 * angle about never, so the plain sum is the wrong yardstick - using it made every peak a
 * third of what the amplitude said it would be.
 */
private const val LOBE_PEAK = 0.55f

/**
 * How much of the resting depth is there regardless, and how much the loudness adds.
 *
 * So that "at rest" is not one flat idle for a whisper and a wall of sound alike. A quiet
 * intro sits at [REST_FLOOR] of whatever the rest control says; a loud passage with no
 * transient in it sits at all of it.
 */
private const val REST_FLOOR = 0.35f
private const val REST_LOUD = 0.65f

/**
 * Where the wave sits, as a fraction of the ring, at the two ends of its control.
 *
 * The floor leaves room for the troughs. At the old one the wave's low side reached back over
 * the edge of the artwork, which is the one thing this face will not spend.
 */
private const val WAVE_OFF_MIN = 0.06f
private const val WAVE_OFF_SPAN = 0.26f

/** How deep the lobes get at the very top of the size control. */
private const val WAVE_AMP_SPAN = 0.44f

/** How far out the shock ring travels, as a fraction of where the wave sits. */
private const val SHOCK_REACH = 0.34f

/** How long the highlight takes to go round. Inside the range most music sits in. */
private const val SWEEP_MS = 2_400

/** And the slower swell underneath it. Not a multiple of the sweep, so they never line up. */
private const val BREATH_MS = 5_600
