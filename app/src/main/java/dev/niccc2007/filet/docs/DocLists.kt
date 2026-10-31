// Ported from mikepenz/multiplatform-markdown-renderer - MarkdownList.kt and BulletHandler.kt
// (https://github.com/mikepenz/multiplatform-markdown-renderer), whose list rendering descends from
// MarkdownComposer (Copyright Erik Hellman, MIT, https://github.com/ErikHellman/MarkdownComposer).
// Copyright Mike Penz and contributors.
// SPDX-License-Identifier: Apache-2.0
//
// Changed from the original: items arrive flattened with their depth (see `MarkdownReader.list`),
// so the marker is chosen per item rather than per nested list; the bullet glyph cycles with depth
// as the original's bullet handler does, ordered numbers are right-aligned in a column as wide as
// the list's widest number, and a task item's box is drawn where its bullet would be. Display
// only - the box is not a control, because ticking it would mean writing the file.
package dev.niccc2007.filet.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

internal object DocLists {

    /** Solid, hollow, square, round again - what GitHub's stylesheet steps through by depth. */
    private val BULLETS = arrayOf("•", "◦", "▪")

    fun bullet(depth: Int): String = BULLETS[depth.coerceAtLeast(0) % BULLETS.size]

    /** The marker an item shows: the file's own, its number, or its depth's bullet. */
    fun marker(item: Block.Item): String = item.marker.ifEmpty {
        if (item.ordered && item.number > 0) "${item.number}." else bullet(item.depth)
    }

    /**
     * How wide the marker column is, in dp.
     *
     * The same for every item of a list, so the text after `9.` and after `10.` starts in one
     * place - which is what right-aligning the numbers is for. A bullet's column is the indent a
     * nested level steps in by, so a sub-list's bullet sits under its parent's text.
     */
    fun markerWidth(item: Block.Item): Dp = when {
        item.task != null -> INDENT
        item.ordered -> maxOf(INDENT, (item.markerChars.coerceAtLeast(marker(item).length) * 7.4f + 6f).dp)
        else -> INDENT
    }

    /** What one level of nesting steps in by: the width of a bullet's column. */
    val INDENT = 22.dp
}

/** A task item's box, ticked or not. */
@Composable
internal fun TaskBox(checked: Boolean) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(13.dp)
            .clip(shape)
            .background(if (checked) colors.accent else Color.Transparent)
            .border(1.2.dp, if (checked) colors.accent else colors.fg3, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary)
        }
    }
}
