package dev.niccc2007.filet.playback

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The vinyl the Aurora face's artwork sits in the middle of.
 *
 * ## Why this exists
 *
 * Aurora's disc was a square cover cropped to a circle with a hole punched in the middle, and
 * it read as a cropped cover with a hole in it. A hole is not what makes a record a record -
 * the rim is. A vinyl is black for most of its radius; the picture only ever lives on the
 * label in the middle. So the artwork is pushed back to [LABEL] of the radius and everything
 * outside it goes dark, with grooves on it.
 *
 * ## What is drawn, from the outside in
 *
 * - a sheen across the black, so the rim is not a flat disc
 * - the grooves: fine rings, deliberately uneven, fading as they approach the label
 * - the lead-in: one wider gap, which is the thing that says "this is where the needle goes"
 * - a rim light in the track's own colour, brighter while it is turning
 * - the label edge: a hairline, and a soft shadow cast onto the vinyl by the paper
 *
 * The artwork underneath is left alone - this draws ON it. That way the cover is still the
 * cover and nothing has to be re-cropped or re-decoded.
 */
@Composable
fun RecordSurface(tint: Color, turning: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val label = r * LABEL

        // The vinyl. Not pure black - a record is a very dark grey with a lot of sheen, and
        // pure black against a dark page loses its edge entirely.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                LABEL to Color.Transparent,
                (LABEL + 0.02f) to VINYL,
                1f to VINYL,
                center = centre,
                radius = r,
            ),
            radius = r,
            center = centre,
        )
        // The sheen, off to one side, so the black has a direction to it.
        drawCircle(
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.07f),
                0.42f to Color.Transparent,
                0.62f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.045f),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            radius = r,
            center = centre,
        )

        // The grooves. Spaced by a varying step rather than evenly: a record's bands are not
        // regular, and a perfectly even set of rings reads as a target.
        var radius = r * 0.985f
        var i = 0
        while (radius > label * 1.06f) {
            val reach = ((radius - label) / (r - label)).coerceIn(0f, 1f)
            drawCircle(
                color = Color.White.copy(alpha = GROOVE * (0.35f + 0.65f * reach)),
                radius = radius,
                center = centre,
                style = Stroke(width = 1f),
            )
            // One wider gap, a third of the way in: the lead-in between two bands.
            radius -= if (i == LEAD_IN) r * 0.035f else r * STEP
            i++
        }

        // The rim light, in the track's colour, lifting as the platter comes up to speed.
        drawCircle(
            color = tint.copy(alpha = 0.10f + 0.16f * turning),
            radius = r - 1f,
            center = centre,
            style = Stroke(width = r * 0.012f),
        )

        // The label's own edge, and the shadow the paper casts on the vinyl around it.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.86f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.45f),
                center = centre,
                radius = label * 1.14f,
            ),
            radius = label * 1.14f,
            center = centre,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = label,
            center = centre,
            style = Stroke(width = 1.4f),
        )
    }
}

/** How much of the radius the artwork keeps. A 7-inch label is about this much of one. */
private const val LABEL = 0.42f

/** A record is not black, it is a very dark grey with the light moving on it. */
private val VINYL = Color(0xFF0D0C0B)

/** How far apart the grooves sit, as a fraction of the radius. */
private const val STEP = 0.014f

/** Which groove is followed by the wider gap. */
private const val LEAD_IN = 11

/** How visible one groove is at the rim. They are meant to be felt, not counted. */
private const val GROOVE = 0.085f
