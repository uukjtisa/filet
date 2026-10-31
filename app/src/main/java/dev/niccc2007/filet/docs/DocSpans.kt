// Ported from saket/extended-spans - RoundedCornerSpanPainter.kt and ExtendedSpanPainter.kt
// (https://github.com/saket/extended-spans), as carried by mikepenz/multiplatform-markdown-renderer.
// Copyright 2023, Saket Narayan.
// SPDX-License-Identifier: Apache-2.0
//
// Changed from the original: the reader marks its code runs itself while building the string (see
// `annotate`), so the general decorate-every-span pipeline is gone and only the measuring and the
// drawing are kept - the per-line boxes of a range, and a rounded rectangle behind each, rounded
// only at the two ends of a run that wraps.
package dev.niccc2007.filet.docs

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection

/**
 * Inline code as a rounded chip behind its text.
 *
 * Why not `SpanStyle(background = ...)`, which is what this replaced: a span background is a
 * rectangle exactly the line's height, so a run of code on one line reads as a highlighter stroke,
 * two on consecutive lines touch, and nothing rounds. The chip is what GitHub draws, and it is what
 * makes `code` in a sentence read as a thing rather than as a colour.
 */
internal object CodeChips {

    /** The per-line boxes [from]..[to] covers, in the text's own coordinates. */
    fun boxes(layout: TextLayoutResult, from: Int, to: Int): List<Rect> {
        val textLength = layout.layoutInput.text.length
        val start = from.coerceIn(0, textLength)
        val end = to.coerceIn(0, textLength)
        if (start >= end) return emptyList()
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(end)
        // Compose has no per-line direction; like the original, this assumes the paragraph runs
        // one way, which is true of everything this reader is handed.
        val ltr = layout.getParagraphDirection(start) == ResolvedTextDirection.Ltr
        val out = ArrayList<Rect>(last - first + 1)
        for (line in first..last) {
            val left = if (line == first) layout.getHorizontalPosition(start, usePrimaryDirection = ltr) else layout.getLineLeft(line)
            val right = if (line == last) layout.getHorizontalPosition(end, usePrimaryDirection = ltr) else layout.getLineRight(line)
            // A range that ends exactly at a line's start contributes an empty box on that line.
            if (right - left > 0.5f) out += Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line))
        }
        return out
    }

    /**
     * Draw a chip behind every range in [ranges], once [layout] has arrived.
     *
     * @param padX how far the chip reaches past its text either side, in pixels.
     * @param inset how far it stays inside the line above and below, so stacked lines never touch.
     */
    fun Modifier.codeChips(
        layout: () -> TextLayoutResult?,
        ranges: List<IntRange>,
        color: Color,
        radius: Float,
        padX: Float,
        inset: Float,
    ): Modifier {
        if (ranges.isEmpty()) return this
        val path = Path()
        return drawBehind {
            val result = layout() ?: return@drawBehind
            val corner = CornerRadius(radius)
            for (r in ranges) {
                val boxes = boxes(result, r.first, r.last + 1)
                boxes.forEachIndexed { i, box ->
                    path.rewind()
                    path.addRoundRect(
                        RoundRect(
                            rect = Rect(box.left - padX, box.top + inset, box.right + padX, box.bottom - inset),
                            topLeft = if (i == 0) corner else CornerRadius.Zero,
                            bottomLeft = if (i == 0) corner else CornerRadius.Zero,
                            topRight = if (i == boxes.lastIndex) corner else CornerRadius.Zero,
                            bottomRight = if (i == boxes.lastIndex) corner else CornerRadius.Zero,
                        ),
                    )
                    drawPath(path, color)
                }
            }
        }
    }
}
