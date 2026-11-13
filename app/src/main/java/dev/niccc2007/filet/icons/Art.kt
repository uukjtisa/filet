package dev.niccc2007.filet.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import java.util.concurrent.ConcurrentHashMap

// The pieces every icon is drawn from, and one way of drawing them.
//
// The art is SVG path data copied out of the icon mock by tools/make-icon-art.mjs (see
// IconArtData.kt), so a shape here is the shape that was approved. It is drawn through a
// DrawScope, which serves both places an icon appears: a Canvas in the app, and a bitmap for a
// home-screen shortcut through CanvasDrawScope. One renderer, so the two cannot drift.

/** An accent ramp: its light, mid and deep tones. */
internal class Accent(light: Long, mid: Long, deep: Long) {
    val light = Color(light)
    val mid = Color(mid)
    val deep = Color(deep)

    /** The mock's `acc-` gradient: light, mid and deep, top to bottom of a shape. */
    val ramp: Ink = Ink.Ramp(arrayOf(0f to this.light, 0.5f to this.mid, 1f to this.deep))
}

internal data class Stop(val offset: Float, val color: Long, val alpha: Float)

/** A named gradient from the mock, top to bottom of whatever it fills. */
internal class Gradient(val radial: Boolean, stops: List<Stop>) {
    val ink: Ink = Ink.Ramp(stops.map { it.offset to Color(it.color).copy(alpha = it.alpha) }.toTypedArray())
}

/**
 * One shape of a glyph, on its 24-unit box.
 *
 * @param stroke drawn as a line of that width rather than filled, when above zero.
 * @param evenOdd shapes inside it are holes.
 * @param light painted with the pale tone rather than the main one.
 */
internal data class GlyphPart(
    val d: String,
    val stroke: Float = 0f,
    val evenOdd: Boolean = false,
    val alpha: Float = 1f,
    val light: Boolean = false,
)

/** A file-type icon family: its name, its accent and its glyph. */
internal data class TypeFamily(val label: String, val accent: String, val glyph: String?)

/** Where a thing stands in a folder: its centre and its lean in degrees. */
internal data class Slot(val x: Float, val y: Float, val lean: Float)

/** An emblem as a glyph: what the picker shows, and what a folder wears where no scene is drawn. */
internal data class Emblem(val id: String, val label: String, val group: String, val parts: List<GlyphPart>)

/**
 * One shape of an emblem drawn as a scene, on its 40 by 30 box.
 *
 * @param tone m the colour's ramp, l its pale tone, d its deep tone, k darker still, w paper white,
 *   g gold, r alert red.
 */
internal data class RichPart(
    val tone: Char,
    val d: String,
    val stroke: Float = 0f,
    val evenOdd: Boolean = false,
    val alpha: Float = 1f,
)

/** A standard folder's look: its path from the top of a drive, its emblem and its colour. */
internal data class DefaultFolderArt(val place: String, val emblem: String, val colour: String)

/** How a shape is painted. */
internal sealed interface Ink {
    data class Solid(val color: Color) : Ink

    /** A vertical gradient over the shape's own height, as the mock's gradients are. */
    class Ramp(val stops: Array<Pair<Float, Color>>) : Ink
}

internal fun ink(color: Color): Ink = Ink.Solid(color)

/** A gradient from the mock, by its id. Fails loudly on a typo rather than drawing nothing. */
internal fun grad(id: String): Ink = GRADIENTS[id]?.ink ?: error("no gradient $id")

internal fun accent(name: String): Accent = ACCENTS[name] ?: ACCENTS.getValue("steel")

/** Toward [to] by [f]: the mock's `mix`. */
internal fun mix(a: Color, to: Color, f: Float) = Color(
    red = a.red + (to.red - a.red) * f,
    green = a.green + (to.green - a.green) * f,
    blue = a.blue + (to.blue - a.blue) * f,
    alpha = a.alpha,
)

/** Parsed paths, by their data and fill rule. Parsing is the slow part, and the same few strings are drawn over and over. */
private val paths = ConcurrentHashMap<String, Path>()

internal fun path(d: String, evenOdd: Boolean = false): Path =
    paths.getOrPut(if (evenOdd) "e$d" else d) {
        PathParser().parsePathString(d).toPath().apply {
            fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        }
    }

/** A rounded rectangle as path data: the mock's `RR`. */
internal fun rr(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}" +
        "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

/** A circle as path data: the mock's `C`. */
internal fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},${cy}a$r,$r 0 1 0 ${2 * r},0a$r,$r 0 1 0 ${-2 * r},0z"

private fun Ink.brush(bounds: Rect): Brush {
    // Inside the app this is null and nothing is turned; see BrandTone.drawing.
    val tone = BrandTone.drawingIn()
    return when (this) {
        is Ink.Solid -> androidx.compose.ui.graphics.SolidColor(tone?.of(color) ?: color)
        is Ink.Ramp -> Brush.verticalGradient(
            *(if (tone == null) stops else Array(stops.size) { i -> stops[i].first to tone.of(stops[i].second) }),
            startY = bounds.top,
            endY = bounds.bottom,
        )
    }
}

/** Fill [d] with [ink], with an optional rim around it. */
internal fun DrawScope.fill(
    d: String,
    ink: Ink,
    alpha: Float = 1f,
    evenOdd: Boolean = false,
    rim: Color? = null,
    rimWidth: Float = 0.6f,
) {
    val p = path(d, evenOdd)
    drawPath(p, ink.brush(p.getBounds()), alpha = alpha)
    if (rim != null) drawPath(p, BrandTone.drawingIn()?.of(rim) ?: rim, style = Stroke(rimWidth, join = StrokeJoin.Round))
}

/** Stroke [d] as a line. */
internal fun DrawScope.line(
    d: String,
    color: Color,
    width: Float,
    alpha: Float = 1f,
    cap: StrokeCap = StrokeCap.Round,
) {
    val inTone = BrandTone.drawingIn()?.of(color) ?: color
    drawPath(path(d), inTone, alpha = alpha, style = Stroke(width, cap = cap, join = StrokeJoin.Round))
}

/** The soft shadow on the ground under an object: a radial fade squashed into an ellipse. */
internal fun DrawScope.ground(cx: Float, cy: Float, rx: Float, ry: Float) {
    withTransform({ scale(1f, ry / rx, Offset(cx, cy)) }) {
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent,
                center = Offset(cx, cy), radius = rx,
            ),
            radius = rx,
            center = Offset(cx, cy),
        )
    }
}

/** Draw [block] as one layer at [alpha]: overlapping shapes fade together, as an SVG group does. */
internal inline fun DrawScope.layer(alpha: Float, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size).inflate(size.maxDimension), Paint().apply { this.alpha = alpha })
    block()
    canvas.restore()
}

/** Draw [block] on a 108-unit grid seen through the mock's view box, scaled to fill this scope. */
internal inline fun DrawScope.onGrid(
    viewX: Float = 10f,
    viewY: Float = 12f,
    view: Float = 88f,
    block: DrawScope.() -> Unit,
) {
    val k = size.minDimension / view
    withTransform({
        scale(k, k, Offset.Zero)
        translate(-viewX, -viewY)
    }) { block() }
}

/**
 * A glyph's parts, painted: the mock's `glyph`.
 *
 * @param fill the main ink, for filled parts.
 * @param stroke the colour of stroked parts.
 * @param light the pale tone, for parts marked light.
 * @param edge a thin rim on the filled parts that are not light, or none.
 */
internal fun DrawScope.glyph(parts: List<GlyphPart>, fill: Ink, stroke: Color, light: Color, edge: Color? = null) {
    for (p in parts) {
        if (p.stroke > 0f) {
            line(p.d, if (p.light) light else stroke, p.stroke, alpha = p.alpha)
        } else {
            fill(
                p.d,
                if (p.light) ink(light) else fill,
                alpha = p.alpha,
                evenOdd = p.evenOdd,
                rim = if (!p.light) edge else null,
            )
        }
    }
}

/**
 * A glyph with a soft drop under it, centred on [cx], [cy] at [scale]: the mock's `emblemOn`.
 * The depth the tab icons have, kept to shapes and opacity.
 */
internal fun DrawScope.emblemGlyph(parts: List<GlyphPart>, colour: String, cx: Float, cy: Float, scale: Float) {
    val a = accent(colour)
    val x = cx - 12f * scale
    val y = cy - 12f * scale
    layer(0.4f) {
        withTransform({ translate(x, y + scale * 1.1f); scale(scale, scale, Offset.Zero) }) {
            glyph(parts, ink(a.deep), a.deep, a.deep)
        }
    }
    withTransform({ translate(x, y); scale(scale, scale, Offset.Zero) }) {
        glyph(parts, a.ramp, a.mid, a.light, edge = a.deep)
    }
}
