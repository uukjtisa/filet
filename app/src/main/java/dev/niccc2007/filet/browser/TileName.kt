package dev.niccc2007.filet.browser

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * How a grid tile's name is sized: whole, never cut with an ellipsis. Lists share the fitting -
 * see [ListName] and [FittedName].
 *
 * Every tile gives its name the same fixed area - as tall as the tallest step below - so icons
 * stay level along a row and a long name cannot push into the row underneath. Within it each
 * name picks its own size: the first step it fits at wins, so a short name stays large beside a
 * long one that had to shrink. A name that does not fit even the last step keeps three lines
 * and fades out at the end of the third, rather than ending in dots that hide which file it is.
 *
 * Sizes here are REAL sizes, as they land on the glass. A pane that is drawn at a reduced
 * density - see `SplitFitted` - would otherwise shrink the type along with everything else, so
 * a step is asked for at its real size divided by that reduction, and nothing lands below
 * [FLOOR_SP].
 */
object TileName {

    /** A size and how many lines it may take. [sp] is real, on-glass. */
    data class Step(val sp: Float, val lines: Int) {
        val lineHeight: Float get() = sp * LINE
    }

    /**
     * Where each grid size starts: Small 11, Medium 13, Large 15. A name keeps its grid's size
     * whenever it fits, so a bigger grid is bigger type and not just bigger icons.
     */
    fun startFor(step: dev.niccc2007.filet.data.ViewStep): Float = when (step) {
        dev.niccc2007.filet.data.ViewStep.SMALL_GRID -> 11f
        dev.niccc2007.filet.data.ViewStep.LARGE_GRID -> 15f
        else -> 13f
    }

    /** At this size and below a name may take a third line; above it, two. */
    const val THIRD_LINE_SP = 10f

    /** Line height as a multiple of the size. */
    const val LINE = 1.25f

    /** The smallest real size anywhere. */
    const val FLOOR_SP = 9f

    /** The smallest on a wide screen, where five or more across leaves each tile its width. */
    const val WIDE_FLOOR_SP = 9.5f

    /** Below this real tile width a name starts a step down - a split half, two across. */
    const val NARROW_TILE_DP = 100f

    /** How far the fade on an overflowing third line runs. */
    const val FADE_DP = 24f

    /**
     * The steps for a tile [realTileDp] wide on the glass, from [startSp] - see [startFor] - a
     * point at a time down to the floor: two lines while the size is above [THIRD_LINE_SP], three
     * from there. [wide] is a screen showing five or more across, which keeps a higher floor. A
     * narrow tile - a split half, two across - starts no higher than 11.
     */
    fun ladder(realTileDp: Float, wide: Boolean, startSp: Float = 13f): List<Step> {
        val start = if (realTileDp < NARROW_TILE_DP) minOf(startSp, 11f) else startSp
        val floor = if (wide) WIDE_FLOOR_SP else FLOOR_SP
        val out = ArrayList<Step>()
        var sp = start
        while (sp > floor) {
            out += Step(sp, if (sp <= THIRD_LINE_SP) 3 else 2)
            sp -= 1f
        }
        out += Step(floor, 3)
        return out.distinct()
    }

    /** The height every tile's name area takes, in real sp: the tallest step in [ladder]. */
    fun areaSp(ladder: List<Step>): Float = ladder.maxOf { it.lineHeight * it.lines }

    /** The size to ask for so that [realSp] lands on the glass under a density [scale]. */
    fun nominal(realSp: Float, scale: Float): Float = if (scale > 0f) realSp / scale else realSp

    /**
     * The first step [overflows] says nothing about, measured at its nominal size.
     *
     * [measure] lays the name out at a nominal size and line count; [overflows] reads that
     * layout. Returned with the layout, so the winner is drawn from the measurement that chose
     * it rather than laid out a second time. When every step overflows, the last one comes
     * back with `fade = true`.
     */
    fun <T> fit(
        ladder: List<Step>,
        scale: Float,
        measure: (nominalSp: Float, lines: Int) -> T,
        overflows: (T) -> Boolean,
    ): Fit<T> {
        var last: T? = null
        for (step in ladder) {
            val laid = measure(nominal(step.sp, scale), step.lines)
            if (!overflows(laid)) return Fit(step, laid, fade = false)
            last = laid
        }
        @Suppress("UNCHECKED_CAST")
        return Fit(ladder.last(), last as T, fade = true)
    }

    data class Fit<T>(val step: Step, val layout: T, val fade: Boolean)
}


/**
 * The same fitting for a list row's name.
 *
 * A list keeps the line count it was given - its step and the Name lines setting decide that,
 * see `nameLineCount` - and gives up size instead, a point at a time, down to [FLOOR_SP]. Past
 * that the name fades rather than ending in dots, exactly as a tile's does.
 */
object ListName {

    /** The smallest a list name gets before it fades. */
    const val FLOOR_SP = 11f

    /** From [startSp] down to [FLOOR_SP] a point at a time, all at [lines]. */
    fun ladder(startSp: Float, lines: Int): List<TileName.Step> {
        if (startSp <= FLOOR_SP) return listOf(TileName.Step(startSp, lines))
        val out = ArrayList<TileName.Step>()
        var sp = startSp
        while (sp > FLOOR_SP) {
            out += TileName.Step(sp, lines)
            sp -= 1f
        }
        out += TileName.Step(FLOOR_SP, lines)
        return out
    }
}

/**
 * The extension a faded name keeps in sight - the `.pdf` of a long report - or null.
 *
 * Which file it is lives at both ends of a long name: the start says what, the extension says
 * what kind. A folder has none, a dotfile's only dot starts its name rather than ending it, and
 * anything longer than [MAX] after the last dot is part of the name, not a type.
 */
object NameEnd {
    const val MAX = 8

    fun extensionOf(name: String, isDir: Boolean): String? {
        if (isDir) return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val ext = name.substring(dot)
        return if (ext.length - 1 <= MAX && ext.none { it.isWhitespace() }) ext else null
    }
}

/** A grid tile's name, fitted by [TileName]: centred, in the fixed name area, at real sizes. */
@Composable
fun TileNameText(
    name: String,
    color: Color,
    wide: Boolean,
    modifier: Modifier = Modifier,
    isDir: Boolean = false,
    startSp: Float = 13f,
) {
    val glass = LocalContext.current.resources.displayMetrics.density
    FittedName(
        text = AnnotatedString(name),
        color = color,
        ladderKey = wide to startSp,
        ladderFor = { widthPx -> TileName.ladder(widthPx / glass, wide, startSp) },
        realSizes = true,
        fixedArea = true,
        align = TextAlign.Center,
        lineHeightFor = { it * TileName.LINE },
        extension = NameEnd.extensionOf(name, isDir),
        modifier = modifier,
    )
}

/**
 * A list row's name, fitted by [ListName]: start-aligned, as tall as the lines it takes.
 *
 * @param startSp the row's own name size, which a name keeps whenever it fits.
 * @param lines how many lines the row allows it.
 * @param align Start for a row; Center for a name set under a picture, the player's title.
 */
@Composable
fun ListNameText(
    text: AnnotatedString,
    color: Color,
    startSp: Float,
    lines: Int,
    modifier: Modifier = Modifier,
    isDir: Boolean = false,
    fontWeight: FontWeight? = null,
    lineHeightFor: (Float) -> Float = { it + 4f },
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    align: TextAlign = TextAlign.Start,
) = FittedName(
    text = text,
    color = color,
    ladderKey = startSp to lines,
    ladderFor = { ListName.ladder(startSp, lines) },
    realSizes = false,
    fixedArea = false,
    align = align,
    lineHeightFor = lineHeightFor,
    extension = NameEnd.extensionOf(text.text, isDir),
    fontWeight = fontWeight,
    fontFamily = fontFamily,
    modifier = modifier,
)

/** [ListNameText] for a plain name. */
@Composable
fun ListNameText(
    name: String,
    color: Color,
    startSp: Float,
    lines: Int = 1,
    modifier: Modifier = Modifier,
    isDir: Boolean = false,
    fontWeight: FontWeight? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    align: TextAlign = TextAlign.Start,
) = ListNameText(AnnotatedString(name), color, startSp, lines, modifier, isDir, fontWeight, fontFamily = fontFamily, align = align)

/**
 * A name, at the first size its ladder fits it at, faded at the end of its last line when none
 * does - never cut with dots.
 *
 * Measured in the layout pass with a [androidx.compose.ui.text.TextMeasurer] and kept for as
 * long as the text and the width stay the same, so a fling re-draws names and never re-fits
 * them, and no size is ever tried by composing it. The whole name is the semantics text, so a
 * reader hears all of it even where the glass shows a fade.
 *
 * When it fades, the [extension] stays: the stem fades out just short of it and the extension
 * is drawn after the fade, on the last line.
 *
 * @param realSizes ladder sizes are on-glass sizes, compensated for a pane drawn at a reduced
 *   density (tiles). False takes them as given, the way the rest of a list row is (lists).
 * @param fixedArea take the height of the tallest step whatever this name needed, so a row of
 *   tiles keeps its icons level.
 */
@Composable
private fun FittedName(
    text: AnnotatedString,
    color: Color,
    ladderKey: Any,
    ladderFor: (widthPx: Int) -> List<TileName.Step>,
    realSizes: Boolean,
    fixedArea: Boolean,
    align: TextAlign,
    lineHeightFor: (Float) -> Float,
    extension: String?,
    modifier: Modifier,
    fontWeight: FontWeight? = null,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val glass = LocalContext.current.resources.displayMetrics.density
    // Below 1 when the pane is drawn at a reduced density; 1 everywhere else.
    val scale = if (realSizes) density.density / glass else 1f
    val holder = remember(text, color, ladderKey, density, fontWeight, fontFamily, extension) { NameHolder() }
    Spacer(
        modifier
            .semantics { this.text = AnnotatedString(text.text) }
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val ladder = ladderFor(width)
                fun style(sp: Float) = TextStyle(
                    color = color,
                    fontSize = sp.sp,
                    lineHeight = lineHeightFor(sp).sp,
                    textAlign = align,
                    fontWeight = fontWeight,
                    fontFamily = fontFamily,
                )
                if (holder.width != width) {
                    holder.width = width
                    val fit = TileName.fit(
                        ladder,
                        scale,
                        measure = { sp, lines ->
                            measurer.measure(
                                text,
                                style = style(sp),
                                overflow = TextOverflow.Clip,
                                maxLines = lines,
                                constraints = Constraints.fixedWidth(width),
                            )
                        },
                        overflows = { it.hasVisualOverflow },
                    )
                    holder.fit = fit
                    holder.end = if (fit.fade && extension != null) {
                        measurer.measure(
                            AnnotatedString(extension),
                            style = style(TileName.nominal(fit.step.sp, scale)).copy(textAlign = TextAlign.Start),
                            maxLines = 1,
                            softWrap = false,
                        )
                    } else {
                        null
                    }
                }
                val laid = holder.fit!!.layout
                val heightPx = if (fixedArea) {
                    val tallest = ladder.maxOf { lineHeightFor(it.sp) * it.lines }
                    ceil(TileName.nominal(tallest, scale).sp.toPx()).toInt()
                } else {
                    laid.size.height
                }
                val placeable = measurable.measure(Constraints.fixed(width, heightPx))
                layout(width, heightPx) { placeable.place(0, 0) }
            }
            // Offscreen only so the fade can take alpha away from what is already drawn.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val fit = holder.fit ?: return@drawBehind
                val laid = fit.layout
                drawText(laid)
                if (!fit.fade || laid.lineCount == 0) return@drawBehind
                val line = laid.lineCount - 1
                val top = laid.getLineTop(line)
                val bottom = laid.getLineBottom(line)
                val tail = holder.end
                val right = laid.getLineRight(line).coerceAtMost(size.width)
                val cut = if (tail != null) (right - tail.size.width).coerceAtLeast(0f) else right
                val run = TileName.FADE_DP.dp.toPx().coerceAtMost(cut)
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = cut - run, endX = cut),
                    topLeft = Offset(cut - run, top),
                    size = Size(run, bottom - top),
                    blendMode = BlendMode.DstIn,
                )
                if (tail != null) {
                    // What the stem had under the extension goes, and the extension takes its place.
                    drawRect(Color.Black, Offset(cut, top), Size(size.width - cut, bottom - top), blendMode = BlendMode.Clear)
                    drawText(tail, topLeft = Offset(cut, top + (bottom - top - tail.size.height) / 2f))
                }
            },
    )
}

/** The fitted layout for one width. Read in draw, written in layout; never state. */
private class NameHolder {
    var width = -1
    var fit: TileName.Fit<TextLayoutResult>? = null
    var end: TextLayoutResult? = null
}
