package dev.niccc2007.filet.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a pane of a given width is allowed to render.
 *
 * Each pane is its own container (mock round 3): a 180dp pane lays out like a 180dp screen,
 * not like the phone it is on. Below 270dp it *overrides the view step* entirely, because
 * six steps need room a narrow pane does not have.
 */
data class PaneMetrics(val step: ViewStep, val showDate: Boolean, val showSize: Boolean, val showChips: Boolean) {
    companion object {
        fun forWidth(widthDp: Int, requested: ViewStep) = PaneMetrics(
            step = if (widthDp < 270 && requested.isGrid) ViewStep.LIST
            else if (widthDp < 200) ViewStep.COMPACT_LIST
            else requested,
            showDate = widthDp >= 330,
            showSize = widthDp >= 180,
            showChips = widthDp >= 180,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier = Modifier,
    /** Off in the two compact list steps, where the glyph is only a few pixels wide. */
    thumbnails: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    val bg by animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            else -> Color.Transparent
        },
        animationSpec = Motion.fast(),
        label = "rowBackground",
    )
    val step = metrics.step
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // ORDER MATTERS, and it is not the usual convention.
        //
        // `modifier` here carries the drag detector, and it goes AFTER `combinedClickable`
        // rather than before it. Compose delivers PointerEventPass.Main from the innermost
        // modifier outwards, so whichever pointer modifier is last in the chain sees the
        // gesture first - and with the drag first, `combinedClickable` won every long press,
        // selected the row, and no drag ever started. That is the whole reason dragging a
        // file between panes appeared to do nothing.
        //
        // The drag detector then owns the long press. It selects on onDragStart, so nothing
        // is lost; see the call sites, which pass no onLongClick when they pass a drag.
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = step.rowHeight.dp)
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            .then(modifier)
            .padding(horizontal = 10.dp, vertical = if (step.twoLine) 6.dp else 3.dp),
    ) {
        FileThumb(
            node = node,
            size = step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
            enabled = thumbnails,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                fontSize = step.nameSize.sp,
                lineHeight = (step.nameSize + 4).sp,
                maxLines = if (step.twoLine) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.twoLine) {
                Text(
                    text = subtitle(node),
                    fontSize = step.metaSize.sp,
                    color = colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (metrics.showSize && !node.isDir) {
            Text(
                text = humanSize(node.size),
                fontSize = step.metaSize.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg3,
                textAlign = TextAlign.End,
                modifier = Modifier.width(62.dp),
            )
        }
        if (metrics.showDate) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (node.mtime > 0) DATE.format(Date(node.mtime)) else "",
                fontSize = step.metaSize.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg3,
                maxLines = 1,
                modifier = Modifier.width(92.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier = Modifier,
    /** Off in the two compact list steps, where the glyph is only a few pixels wide. */
    thumbnails: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    val step = metrics.step
    val bg by animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            else -> Color.Transparent
        },
        animationSpec = Motion.fast(),
        label = "tileBackground",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        // The drag detector goes last - see the note in FileRow. Same trap, same fix.
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
            .then(modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        FileThumb(
            node = node,
            size = step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
            enabled = thumbnails,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = node.name,
            fontSize = step.nameSize.sp,
            lineHeight = (step.nameSize + 3).sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Folders deliberately print nothing here: a bare em dash where a size would be
        // reads as a failed lookup rather than as "not applicable".
        if (!node.isDir && metrics.showSize) {
            Text(humanSize(node.size), fontSize = step.metaSize.sp, color = colors.fg3)
        }
    }
}

/** A hit inside a search result list: name plus where it was found. */
@Composable
fun SearchResultRow(
    node: VNode,
    where: String,
    metrics: PaneMetrics,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.sel else Color.Transparent)
            .combinedClickableCompat(onClick, onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        FileThumb(
            node = node,
            size = metrics.step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, fontSize = metrics.step.nameSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(where, fontSize = metrics.step.metaSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.fg3, fontFamily = FontFamily.Monospace)
        }
        if (!node.isDir && metrics.showSize) {
            Text(humanSize(node.size), fontSize = metrics.step.metaSize.sp, fontFamily = FontFamily.Monospace, color = colors.fg3)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun iconTint(kind: FileKind, accent: Color, onSurfaceVariant: Color, fg2: Color): Color =
    if (kind == FileKind.FOLDER) accent else if (kind == FileKind.OTHER) fg2 else onSurfaceVariant

private fun subtitle(node: VNode): String {
    val when_ = if (node.mtime > 0) DATE.format(Date(node.mtime)) else ""
    return if (node.isDir) when_ else listOf(humanSize(node.size), when_).filter { it.isNotEmpty() }.joinToString("  ·  ")
}

private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

fun humanSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "${v.toInt()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** A neutral empty state. Says which of the two empties this is, because they differ. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Filet.colors.fg3, textAlign = TextAlign.Center)
    }
}
