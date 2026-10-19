package dev.niccc2007.filet.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/**
 * The minimised player's light, coming off its progress bar.
 *
 * ## What this replaces, and why the old one looked wrong
 *
 * The strip used the same `livingGlow` the full player does: three soft lights drifting on
 * ellipses across the surface. On a screen-sized face that reads as weather. On a 56dp band it
 * reads as what it is - three circles of colour wandering about behind a row of buttons, with
 * no relationship to anything on screen. Unclipped, they also bled upward into the file list in
 * a shape that had nothing to do with the strip, which is what "an odd awkward circular glow
 * randomly around the thing" is a fair description of.
 *
 * The spill itself was worth keeping. What was wrong was that the light had no SOURCE.
 *
 * ## So it comes off the progress bar
 *
 * The bar is the one thing on the strip that means something and moves on its own, so it is
 * where the light comes from:
 *
 * - **The playhead is the brightest point**, and it travels with the track. A light anchored to
 *   where you are in the song is a light that is about something.
 * - **It radiates UPWARD only**, from the bar's own line, fading out over a few times the
 *   strip's height - so it reads as light thrown off an edge rather than as a halo around a
 *   widget. Nothing is drawn below the bar, because there is nothing down there to light.
 * - **It is wider than it is tall**, which is the shape light makes coming off a line rather
 *   than a point.
 * - **Two slow companions drift along the bar** behind the playhead, so the field breathes
 *   instead of being a single lamp sliding sideways. They are confined to the bar's line; the
 *   drift is along it, never away from it.
 * - **The beat opens it up.** The same signed swing everything else uses, so the light coming
 *   off the bar swells on a drum.
 *
 * ## Why this draws above its own bounds deliberately
 *
 * `drawBehind` is not clipped to the node, so drawing at a negative Y reaches up into the file
 * list. That is the spill, and it is the point.
 *
 * Bug identified here: this note used to say that with the spill off the light was "drawn
 * clipped to the strip". Nothing clipped it. Off only halved the reach, and every lamp is drawn
 * ABOVE the bar, so half the reach was still 1.25 strip-heights of light standing over the file
 * list and beating with the music - the leftover element that ignored the switch. All of this
 * light lives above the strip's top edge, so a clip and "not drawn" are the same picture: off
 * now draws nothing here. The hairline and the strip's own wash are unaffected; they are inside
 * the strip.
 */
@Composable
fun Modifier.stripRadiance(
    base: Color,
    /**
     * How far through the track, 0 to 1 - where the brightest point sits.
     *
     * A function, read inside the draw. As a plain `Float` it was a position read in the
     * strip's body, so every tick of the position put the whole strip - its subcomposition,
     * marquee and five buttons - through a recomposition to move a light a pixel.
     */
    fraction: () -> Float,
    on: Boolean,
    running: Boolean,
    /** The glow's own brightness, as a multiplier. See `glowScale`. */
    strength: Float = 1f,
    /** True lets the light reach up into the list above the strip. */
    spill: Boolean = false,
    /** The beat, SIGNED about the track's own average. See [beatSwing]. */
    pulse: State<Float>? = null,
    pulseDepth: Float = 1f,
): Modifier {
    if (!on || !spill) return this
    val clock = rememberGlowPhases(running)
    // One brush for all three lamps: they are the same hue and differ only in alpha, which is
    // the draw's own. See `GlowLamps` for the per-frame allocation this replaced.
    val brush = remember(base) { unitRadial(base.copy(alpha = 1f), 0.45f, 0.42f) }
    return drawBehind {
        // READ HERE, not above. `Modifier.stripRadiance` is a composable function returning a
        // value, so it has no restart scope of its own - a state read in its body belongs to
        // the caller, which is the strip, which is on screen in every folder. Read in the draw
        // lambda, a frame invalidates the draw phase and nothing recomposes. The same note is
        // on `livingGlow`, and the cost of getting it wrong there was a phone that got hot.
        val swing = if (pulse == null) 0f else pulse.value * pulseDepth
        // Only the bright half reaches the light's size. A band this short has no room to lose
        // brightness and still be seen losing it, which is why the full player dips and this
        // does not.
        val hit = swing.coerceAtLeast(0f)
        val lit = strength * (1f + hit * BEAT_LIFT)

        // The bar is the top edge of the strip, so the light's origin is y = 0 and it travels
        // up from there. Height is the reach, which is a multiple of the band rather than of
        // the screen: the strip knows how tall it is and the list above it does not.
        val reach = size.height * (REACH + hit * BEAT_REACH)
        val head = Offset(size.width * fraction().coerceIn(0f, 1f), 0f)

        // ── the two drifting companions, confined to the bar's line ──
        //
        // Along the bar, never away from it. A light that wanders off the line it is supposed
        // to be coming from is the thing this file exists to stop.
        for (i in 0 until 2) {
            val t = clock.phase(i) * 2f * Math.PI.toFloat()
            val x = size.width * (0.3f + 0.4f * i + 0.22f * cos(t))
            val sway = 1f + 0.18f * sin(t * 1.3f)
            lamp(
                brush = brush,
                alpha = (COMPANION_ALPHA * lit).coerceIn(0f, 1f),
                at = Offset(x, 0f),
                reach = reach * 0.8f * sway,
            )
        }

        // ── the playhead, brightest, and it travels with the music ──
        lamp(
            brush = brush,
            alpha = (HEAD_ALPHA * lit).coerceIn(0f, 1f),
            at = head,
            reach = reach,
        )
    }
}

/**
 * One light thrown off a line.
 *
 * Drawn as a radial gradient squashed into a wide, short rectangle sitting ABOVE the origin. A
 * circle would be a halo; light coming off an edge is an arc much wider than it is tall,
 * brightest where it leaves the edge.
 *
 * Whether it may leave the strip is the caller's clip, not this function's business - which is
 * why there is no spill parameter here.
 */
private fun DrawScope.lamp(brush: Brush, alpha: Float, at: Offset, reach: Float) {
    if (reach <= 1f) return
    // Squashed with a scale around the origin rather than drawn as an ellipse, because a radial
    // brush takes one radius and cannot be given two - and scaling the whole draw keeps the
    // falloff smooth where stretching the stops would band it.
    //
    // The remembered brush is centred on the origin at `UNIT_RADIUS`, so the light's position
    // and reach are the same transform: translate to the origin of the light, then scale the
    // unit gradient out to `reach` - three times wider than tall, as before.
    val k = reach / UNIT_RADIUS
    withTransform({
        translate(at.x, at.y)
        scale(scaleX = WIDTH_RATIO * k, scaleY = k, pivot = Offset.Zero)
    }) {
        drawRect(
            brush = brush,
            // ABOVE the origin only. Nothing is drawn below the bar: the strip's own ground is
            // down there and lighting it from behind only muddies the buttons on it.
            topLeft = Offset(-UNIT_RADIUS, -UNIT_RADIUS),
            size = Size(UNIT_RADIUS * 2f, UNIT_RADIUS),
            alpha = alpha,
        )
    }
}

/**
 * How far the light reaches above the bar, as a multiple of the strip's own height.
 *
 * Two and a half bands, which on a 56dp strip is about 140dp of the list above it - enough to
 * be unmistakably light rather than a border, and short of the half-screen wash that would
 * make file names hard to read.
 */
private const val REACH = 2.5f

/** And how much further a hard beat pushes it. */
private const val BEAT_REACH = 0.9f

/** How much brighter a hard beat makes it. */
private const val BEAT_LIFT = 1.4f

/**
 * How much wider than tall the light is.
 *
 * Three to one. Light leaving a straight edge spreads sideways far more than it travels
 * outward, and a 1:1 falloff is the circle this replaced.
 */
private const val WIDTH_RATIO = 3f

/** The playhead's own alpha, and the two behind it. */
private const val HEAD_ALPHA = 0.30f
private const val COMPANION_ALPHA = 0.14f
