package dev.niccc2007.filet.playback

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin

/**
 * The ring of light round the Halo face's disc, with a wave that answers the drums.
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
     * Whether the disc wears a glow of its own. See `Prefs.discGlow`.
     *
     * Off, what remains is the hairline the artwork needs in order to have an edge.
     */
    glow: Boolean = true,
    /**
     * How much glow is painted, from the same control as the background's. See `glowScale`.
     *
     * Bug identified here: the mist ignored this entirely, so a control named "Glow strength"
     * changed the backdrop and left the brightest glow on the screen alone. One slider has to
     * mean one thing, and the thing it means is how much light this face throws.
     */
    glowScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val running = rememberStatus(playback).value.playing
    val dials = rememberPlayerDials()
    // The sweep is drawn only without a beat map (see SWEEP_UNDER_BEAT), so its clock runs
    // only then. It used to tick twenty times a second under a beat it was never drawn over.
    val sweep = rememberPhase(SWEEP_MS, running && signal == null)
    val breath = rememberPhase(BREATH_MS, running)
    val points = dials.wavePoints

    Spacer(
        modifier.drawWithCache {
            // ## Cost model
            //
            // Per frame (while the beat moves it, at `PlayerDials.pulseFps`; otherwise at the
            // ring's own clocks, `PlayerDials.phaseFps`): one circle, and with a map the wave -
            // `wavePoints` x in-circuit lobes sines into a path that is RESET and refilled,
            // never rebuilt - plus at most two shock circles. Allocates nothing: the path, the
            // paint and every brush are built here, once per size and colour. Strokes go
            // through one remembered `Paint` because a `Stroke` style object per call was an
            // allocation per frame too.
            val centre = Offset(size.width / 2f, size.height / 2f)
            val path = Path()
            val paint = Paint().apply { isAntiAlias = true }
            val sweepBrush = Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.06f to tint.copy(alpha = 0.55f),
                0.11f to Color.White.copy(alpha = 0.30f),
                0.16f to tint.copy(alpha = 0.55f),
                0.26f to Color.Transparent,
                1.00f to Color.Transparent,
                center = centre,
            )
            val waves = WaveBrushes(tint, centre)
            // The rim glow's two passes: a wide soft bloom and a tight bright core. A blur MASK
            // on a stroked circle rather than a radial gradient, because the gradient's stops
            // would have to move with the ring's radius every frame (a new brush per frame),
            // while a mask filter is built once per size and the circle under it can move
            // freely. Blurred mask filters are honoured by the hardware renderer from API 28; on
            // 26-27 the same strokes draw unblurred, as a bright double rim. That is a platform
            // floor, not a defect: a software blur here would run on the CPU every beat frame.
            val r0 = size.minDimension / 2f
            // The rim, as two gradients rather than two blurred strokes.
            //
            // Same reasoning as the mist above, and the same measurement behind it: a
            // `BlurMaskFilter` is a real blur over the stroked area and it is recomputed on
            // every draw, which here means `PlayerDials.pulseFps` times a second - sixty, on a
            // capable phone - over a circle the width of the screen. Three CPU blurs a frame
            // for as long as the player is open is most of why the maximised player ran hot.
            //
            // The widths are baked into the stops, so a beat no longer thickens the rim; it
            // brightens it, throws the ring outward (`RING_KICK`) and pushes the mist. Those
            // were always the parts that read, and none of them costs a blur.
            val bloom = ringBrush(tint, RIM_BLOOM_HALF)
            // The mist: a very wide, very soft stroke sitting ON the disc's edge.
            //
            // ## The shape this had to be, and the one it was
            //
            // It was three radial gradients centred on the disc. That is the obvious way to
            // make a glow and it cannot work here, for a reason that is only obvious once it
            // is on a phone: a centred radial has its bright core at the CENTRE, and the
            // centre of this disc is covered by opaque album art. Everything worth seeing was
            // painted underneath the picture, and the only part that escaped past the rim was
            // the dying tail of the gradient - which arrived at about seven percent alpha, in
            // the same hue as the backdrop it was drawn on, and was therefore invisible.
            //
            // A rim glow is light leaving an edge, so it is drawn as an edge: a stroke on the
            // ring, blurred by a large fraction of the radius. The blur spreads it outward
            // into the background and inward under the art, where the art hides the half that
            // would have been wasted anyway. That is the same shape the minimised strip's
            // light has, which is the one being asked for.
            // Built ONCE per size and colour, and drawn per frame as a scaled circle.
            //
            // The first version of this was two `BlurMaskFilter` strokes, which is the obvious
            // way to blur a ring and the wrong way to do it sixty times a second: a mask filter
            // is a real blur over the stroke's area, it runs on the CPU, and the stroke here is
            // a quarter of the radius wide on a circle that fills the screen. It was measurably
            // hot in the hand within a minute of opening the player.
            //
            // A radial gradient is the same picture and costs a shader. The stops are what make
            // it a RING rather than a disc: transparent through the middle, peaking just
            // outside the artwork's edge, then a long tail into the background. That tail is
            // the mist; the peak is the edge it comes off.
            val mist = Brush.radialGradient(
                0.00f to Color.Transparent,
                MIST_INNER to Color.Transparent,
                MIST_PEAK to tint.copy(alpha = MIST_NEAR_ALPHA),
                MIST_TAIL to tint.copy(alpha = MIST_WIDE_ALPHA),
                1.00f to Color.Transparent,
                center = Offset.Zero,
                radius = UNIT_RADIUS,
            )
            val core = ringBrush(tint, RIM_CORE_HALF)
            onDrawBehind {
                // Read in the draw lambda. See `livingGlow` for what reading it above costs. The
                // beat goes the same way, and reading it here is also what invalidates the draw
                // on every beat without recomposing anything.
                val s = sweep.value
                val b = breath.value
                val r = size.minDimension / 2f
                val swell = sin(b * 2f * PI.toFloat())
                val onBeat = signal != null
                // The ring takes the positive half of the swing only. It is a hairline on the
                // edge of the artwork; dimming it further in the gaps would simply remove it,
                // where the backdrop has the area to lose brightness and still be seen losing it.
                val hit =
                    if (!running || signal == null) 0f
                    else (signal.swing.value * pulseDepth).coerceAtLeast(0f) * 1.5f
                val lit = (0.25f + 0.75f * turning) * (1f + hit * PUNCH)

                // The ring the artwork sits inside, just off its edge so the picture is not touched.
                val ring = r * (1.012f + 0.010f * swell + RING_KICK * hit)
                strokeCircle(
                    paint, centre, ring,
                    tint.copy(alpha = ((0.16f + 0.08f * swell) * lit).coerceIn(0f, 1f)),
                    r * (0.018f + 0.006f * swell + 0.020f * hit),
                )
                // Everything past here is light rather than edge. With the glow off, the disc
                // keeps its rim and nothing else.
                if (!glow) return@onDrawBehind

                // ── the rim glow: light ringing the disc's own border, lit by the beat ──
                //
                // Bug identified here: the radiant edge earlier builds had was the travelling
                // highlight below, and it is switched off whenever a beat map exists
                // (SWEEP_UNDER_BEAT = 0, so it would not be a second rhythm against the drums).
                // With the pulse on, nothing was left on the border but the hairline above - a
                // stroke under two percent of the radius at about a sixth of an alpha. So the
                // glow disappeared exactly when the beat was supposed to light it. This puts a
                // glow ON the border that IS the beat: it sits on the ring, follows its kick
                // outward, and brightens and thickens with the hit. At rest it still glows,
                // softly, so the disc keeps its edge of light through a quiet passage.
                val rimLit = (0.25f + 0.75f * turning) * (RIM_REST + (1f - RIM_REST) * hit.coerceAtMost(1f))

                // ── the mist: the disc's own light, thrown OUTWARD into the space round it ──
                //
                // This is the part that was missing, and the rim is why it was not noticed
                // missing: a rim is an EDGE - two blurred strokes a few percent of the radius
                // wide - so the disc had a bright border and no light coming off it. What the
                // face is meant to look like is the minimised strip's spill, colour leaving
                // the artwork and fading into the background, so the record sits IN something
                // rather than on top of it.
                //
                // Two passes, under the rim: a wide faint one that reaches into the backdrop
                // and a narrower brighter one that keeps the edge itself hot. Both breathe on
                // the slow clock and widen on a beat - the reach grows as well as the
                // brightness, because at this size a change of alpha alone is a flicker and a
                // change of size is the thing the eye catches.
                //
                // Held WELL ABOVE the rim's resting level. The backdrop behind this face is
                // the same colour the mist is, so a glow scaled down like a highlight simply
                // dissolves into it - the brightness here is doing the work the hue cannot.
                // Scaled by the strength control, and bounded. Unbounded, the top of the
                // slider would blow the mist out to a flat disc of colour - this is light in
                // air, and past a point more of it stops looking like more.
                val mistLit = (0.45f + 0.55f * turning) *
                    (MIST_REST + (1f - MIST_REST) * hit.coerceAtMost(1f)) *
                    glowScale.coerceIn(MIST_SCALE_MIN, MIST_SCALE_MAX)
                // One circle. The gradient already holds the shape; the beat moves how far it
                // reaches and how bright it is, both of which are a transform and an alpha
                // rather than a new brush - see the note on `GlowLamps` for why that matters.
                val reach = ring / MIST_PEAK * (1f + MIST_BREATH * swell + MIST_LIFT * hit)
                val k = reach / UNIT_RADIUS
                if (k > 0f && mistLit > 0.002f) {
                    withTransform({
                        translate(centre.x, centre.y)
                        scale(k, k, pivot = Offset.Zero)
                    }) {
                        drawCircle(
                            brush = mist,
                            radius = UNIT_RADIUS,
                            center = Offset.Zero,
                            alpha = mistLit.coerceIn(0f, 1f),
                        )
                    }
                }

                drawRing(bloom, centre, ring, rimLit * 0.55f)
                drawRing(core, centre, ring, rimLit)

                // ── the wave ───────────────────────────────────────────────────────────────
                //
                // Three earlier versions were wrong: a bright arc on a fixed clock (a loading
                // spinner), the last three seconds of the track wrapped round the circle (a fixed
                // shape that only grew), and a standing wave on a symmetric envelope (a permanent
                // ripple). What makes this one answer the music:
                //
                //  1. **The drive is a burst** - instant up, [WAVE_RELEASE_MS] down, so each beat
                //     is its own explosion with dark between them.
                //  2. **The drive is RELATIVE** - the signed swing about this track's own recent
                //     average, so a calm passage genuinely sits at rest.
                //  3. **A harder hit adds harmonics, not just depth** - [LOBES] fade in with the
                //     drive, so a soft beat is two lazy swells and a hard one is eight tight ones.
                //
                // On the rare hit well above the track's own ceiling, one ring of displacement is
                // thrown outward and dies. See [SHOCK_AT].
                if (onBeat && waveSize > 0f) {
                    val seconds = android.os.SystemClock.elapsedRealtime() / 1000.0
                    val base = ring * (1f + WAVE_OFF_MIN + waveGap * WAVE_OFF_SPAN)
                    // BETWEEN two settings rather than scaled by one: rest is where it sits with
                    // nothing happening, size is where a full-strength beat takes it.
                    val drive = (signal!!.blast.value * pulseDepth).coerceIn(0f, 1f)
                    // The resting depth leans on the LOUDNESS, so a quiet intro is flatter than a
                    // loud verse even before a drum lands in either.
                    val rest = waveRest.coerceIn(0f, 1f) * (REST_FLOOR + REST_LOUD * signal.loud.value)
                    val amp = r * WAVE_AMP_SPAN * (rest + (waveSize - rest).coerceAtLeast(0f) * drive)
                    path.reset()
                    for (i in 0..points) {
                        val th = i.toFloat() / points * 2f * PI.toFloat()
                        var d = 0f
                        var peak = 0f
                        for (k in LOBES.indices) {
                            val lobe = LOBES[k]
                            val w = lobe.weight * lobe.entersAt(drive)
                            if (w <= 0f) continue
                            peak += w
                            d += w * sin(lobe.count * th + (lobe.speed * seconds).toFloat())
                        }
                        // Against the TYPICAL peak, not the sum of the weights: harmonics at
                        // unrelated phases almost never line up, and dividing by the sum scaled
                        // the figure down by about three. Summed over the lobes in circuit only,
                        // or fading one in would quietly shrink every other one.
                        val norm = (peak * LOBE_PEAK).coerceAtLeast(0.0001f)
                        // OUTWARD ONLY. Bug identified here: the sum of sines is signed, and it
                        // was added to the radius as it came - so half of every lobe dipped
                        // inside the baseline toward the centre and the figure read as a ripple
                        // breathing both ways. Rectified, the troughs sit flat on the baseline
                        // and only the crests leave it; squared, the crests are narrow spikes
                        // rather than rounded hills, which is the difference between a burst and
                        // a swell. A crest of 1 still reaches the same height as before.
                        val out = (d / norm).coerceIn(0f, 1.25f)
                        val rad = base + amp * out * out
                        val a = th - PI.toFloat() / 2f
                        val x = centre.x + kotlin.math.cos(a) * rad
                        val y = centre.y + kotlin.math.sin(a) * rad
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    val brush = waves.at(lit)
                    // Twice: a wide faint pass for the bloom and a fine one for the line itself -
                    // the same two-pass stroke the tree's guides use, so this reads as part of
                    // the app rather than as a visualiser dropped into it.
                    strokePath(paint, path, brush, r * 0.034f, 0.20f)
                    strokePath(paint, path, brush, r * 0.009f, 1f)

                    // ── the shock: one ring outward on a hit genuinely bigger than normal ──
                    //
                    // Drawn from the impulse's REMAINING value: 1 is the moment it fired, 0 is
                    // gone and out at [SHOCK_REACH]. Fading as the square, so it is bright for
                    // the first instant and then mostly gone.
                    val impulse = signal.shock.value
                    if (impulse > 0f) {
                        val at = base * (1f + SHOCK_REACH * (1f - impulse))
                        val fade = impulse * impulse
                        strokeCircle(
                            paint, centre, at,
                            tint.copy(alpha = (0.40f * fade * lit).coerceIn(0f, 1f)),
                            r * (0.004f + 0.012f * impulse),
                        )
                        strokeCircle(
                            paint, centre, at,
                            tint.copy(alpha = (0.10f * fade * lit).coerceIn(0f, 1f)),
                            r * 0.05f,
                        )
                    }
                }

                // The travelling highlight is the FALLBACK, not the feature. With a beat map it
                // is off: a second rhythm next to a light answering the drums presented as
                // something starting before the beat did.
                val sweepAlpha = if (onBeat) SWEEP_UNDER_BEAT else 1f
                if (sweepAlpha > 0f) {
                    rotate(degrees = s * 360f, pivot = centre) {
                        // `lit` is at most 1 here - the hit is zero without a map - so scaling
                        // the remembered brush by the draw's alpha is the same picture the
                        // per-frame brush drew with `lit` multiplied into each stop.
                        drawCircle(
                            brush = sweepBrush,
                            radius = ring,
                            center = centre,
                            alpha = (lit * sweepAlpha).coerceIn(0f, 1f),
                            style = sweepStroke(r),
                        )
                    }
                }
            }
        },
    )
}

/**
 * The sweep's stroke, cached on its width.
 *
 * The width is a fixed fraction of the radius, so it changes only with size; one `Stroke` per
 * size rather than one per frame.
 */
private var sweepStrokeCache: Stroke = Stroke(width = 0f)
private fun sweepStroke(r: Float): Stroke {
    val w = r * 0.034f
    if (sweepStrokeCache.width != w) sweepStrokeCache = Stroke(width = w)
    return sweepStrokeCache
}

/** One solid-colour stroked circle through the shared paint. Allocates nothing. */
private fun DrawScope.strokeCircle(paint: Paint, centre: Offset, radius: Float, colour: Color, width: Float) {
    if (radius <= 0f || colour.alpha <= 0f) return
    paint.shader = null
    paint.alpha = 1f
    paint.color = colour
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = width
    drawIntoCanvas { it.drawCircle(centre, radius, paint) }
}

/** The path stroked with [brush] at [alpha] through the shared paint. Allocates nothing. */
private fun DrawScope.strokePath(paint: Paint, path: Path, brush: Brush, width: Float, alpha: Float) {
    paint.color = Color.Black
    brush.applyTo(size, paint, alpha)
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = width
    paint.strokeCap = StrokeCap.Round
    paint.strokeJoin = StrokeJoin.Round
    drawIntoCanvas { it.drawPath(path, paint) }
}

/**
 * The wave's sweep gradient at a given brightness, built once per brightness step.
 *
 * Decision: a cache of quantised steps, because the cheap version is wrong here. Each stop is
 * `tint.copy(alpha = k * lit)` and `Color` clamps each one to 1 independently (verified against
 * `ColorKt.Color` in ui-graphics 1.11.2), so above `lit = 1/0.85` the stops saturate one by one
 * and the gradient flattens - which is how a hard beat looks today. A single brush scaled by
 * the draw's alpha cannot reproduce that and would dim every hard beat. Steps of 1/256 put the
 * largest stop's error at 0.85/512, under half of one 8-bit level, so the cached picture is the
 * per-frame picture after rounding. Filled lazily, at most [STEPS] brushes per size and colour.
 */
private class WaveBrushes(private val tint: Color, private val centre: Offset) {
    private val cache = arrayOfNulls<Brush>(STEPS)

    fun at(lit: Float): Brush {
        val i = (lit * PER_UNIT).toInt().coerceIn(0, STEPS - 1)
        cache[i]?.let { return it }
        val l = i.toFloat() / PER_UNIT
        val head = tint.copy(alpha = (0.85f * l).coerceIn(0f, 1f))
        val made = Brush.sweepGradient(
            0.00f to head,
            0.35f to tint.copy(alpha = (0.45f * l).coerceIn(0f, 1f)),
            0.75f to tint.copy(alpha = (0.14f * l).coerceIn(0f, 1f)),
            1.00f to head,
            center = centre,
        )
        cache[i] = made
        return made
    }

    private companion object {
        const val PER_UNIT = 256f

        /** Past 1/0.14, every stop is saturated and every brightness is the same brush. */
        const val STEPS = (PER_UNIT / 0.14f).toInt() + 2
    }
}

/** One soft ring of light through a blurred framework paint. Allocates nothing. */
/**
 * A band of light at [half] either side of [RING_PEAK], as a unit-radius gradient.
 *
 * Built once per size and colour and scaled into place by [drawRing], so the band's shape costs
 * a shader rather than a blur. The peak is at a fixed position along the gradient, which is why
 * the draw scales by `radius / RING_PEAK` rather than by `radius`.
 */
private fun ringBrush(tint: Color, half: Float): Brush = Brush.radialGradient(
    0f to Color.Transparent,
    (RING_PEAK - half).coerceAtLeast(0.001f) to Color.Transparent,
    RING_PEAK to tint.copy(alpha = 1f),
    (RING_PEAK + half).coerceAtMost(0.999f) to Color.Transparent,
    1f to Color.Transparent,
    center = Offset.Zero,
    radius = UNIT_RADIUS,
)

/** One band from [ringBrush], centred on [centre], peaking at [radius]. */
private fun DrawScope.drawRing(brush: Brush, centre: Offset, radius: Float, alpha: Float) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.002f || radius <= 0f) return
    val k = radius / RING_PEAK / UNIT_RADIUS
    withTransform({
        translate(centre.x, centre.y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawCircle(brush = brush, radius = UNIT_RADIUS, center = Offset.Zero, alpha = a)
    }
}

/** Where every band's peak sits along its gradient. One value, so the scale maths is shared. */
private const val RING_PEAK = 0.72f

/**
 * The disc's mist, as positions along its gradient and fractions of the disc's radius.
 *
 * - [MIST_INNER] where the light starts to exist at all, inside the artwork's edge so there is
 *   no seam where the picture stops.
 * - [MIST_PEAK] where it is brightest. The ring is placed here, so this is also what decides
 *   how far the whole figure is scaled - see the draw.
 * - [MIST_TAIL] where the long faint reach into the background begins.
 * - [MIST_BREATH] and [MIST_LIFT] push it outward on the slow clock and on a hit.
 * - [MIST_REST] how much of it shows with no beat at all. Far higher than the rim's resting
 *   level on purpose: the mist is what the disc looks like, not an accent on a drum.
 */
private const val MIST_INNER = 0.56f
private const val MIST_PEAK = 0.70f
private const val MIST_TAIL = 0.82f
private const val MIST_BREATH = 0.020f
private const val MIST_LIFT = 0.07f
private const val MIST_REST = 0.72f

/**
 * How much of each pass reaches the screen.
 *
 * Measured against this face's own backdrop rather than picked. The background behind Halo is
 * the track's colour at the living glow's strength, so the mist is being drawn green on green -
 * at the fractions a highlight would use it disappeared completely, which is what "there is no
 * radiant glow around the disc" was. These are the values at which the light is visibly
 * brighter than what it sits on.
 */
private const val MIST_WIDE_ALPHA = 0.34f
private const val MIST_NEAR_ALPHA = 0.46f

/**
 * How far the strength control may push the mist.
 *
 * Narrower than the control's own range. The background is a wash over a whole screen and can
 * take a multiple of two without becoming anything; a glow this bright already is at the very
 * limit of being light rather than paint, so the top of the slider lifts it by a third and the
 * bottom never takes it below half - a mist that can be dimmed to nothing is the off switch
 * coming back through the strength control.
 */
private const val MIST_SCALE_MIN = 0.55f
private const val MIST_SCALE_MAX = 1.35f

/**
 * The rim glow's shape, as half-widths along the band gradient.
 *
 * The bloom is wide and soft, the core narrow and bright - two passes are what make an edge
 * read as light rather than as paint. [RIM_REST] is how much of it shows with no beat, so the
 * edge never goes dark between hits.
 *
 * These are the blurred strokes' resting extents converted to gradient stops: a stroke `w` wide
 * under a blur of `b` reaches about `w/2 + b` either side of its centre, taken against
 * [RING_PEAK]. The picture is the one that was there; the cost is a shader instead of three
 * CPU blurs a frame.
 */
private const val RIM_BLOOM_HALF = 0.094f
private const val RIM_CORE_HALF = 0.024f
private const val RIM_REST = 0.35f

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

/**
 * How many points go round the wave on a capable phone. Every two degrees: smooth at this
 * size. Below that tier see `PlayerDials.wavePoints`.
 */
internal const val WAVE_POINTS = 180


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
