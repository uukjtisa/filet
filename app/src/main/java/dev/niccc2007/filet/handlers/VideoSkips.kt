package dev.niccc2007.filet.handlers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons

/**
 * The video player's skips, drawn the way other players draw them.
 *
 * Two kinds, in one row with Play: the inner pair moves the video five seconds, as a
 * circular arrow with the seconds inside it; the outer pair goes to the previous or next video
 * of the run, as the bar-and-triangle skip glyphs. The outer pair used to sit in the top bar
 * beside the name, where they were small and hard to hit.
 */

/** A circular arrow, open at the top, its head pointing the way it turns. */
private fun turn(name: String, forward: Boolean): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    val arc = if (forward) "M12 5A8 8 0 1 0 18.93 9" else "M12 5A8 8 0 1 1 5.07 9"
    val head = if (forward) "M9.5 2.5 12 5 9.5 7.5" else "M14.5 2.5 12 5l2.5 2.5"
    for (d in listOf(arc, head)) {
        b.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

private val TurnBack = turn("skipback", forward = false)
private val TurnForward = turn("skipforward", forward = true)

/** Back or forward by [seconds], with the number inside the arrow. */
@Composable
internal fun SecondsSkip(forward: Boolean, seconds: Int, size: Dp, onClick: () -> Unit) {
    val glyph = size * 0.62f
    val label = if (forward) "Forward $seconds seconds" else "Back $seconds seconds"
    SkipDisc(size, label, onClick = onClick) {
        Icon(if (forward) TurnForward else TurnBack, label, tint = Color.White, modifier = Modifier.size(glyph))
        // The arc's centre is a unit below the glyph's, out of 24.
        Text(
            "$seconds",
            color = Color.White,
            fontSize = (glyph.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(y = glyph / 24f).clearAndSetSemantics {},
        )
    }
}

/**
 * The previous or next video of the run. Nothing outside a run; at either end of one it is
 * still drawn, dimmed and not pressable, so the row keeps its shape and Play stays centred.
 */
@Composable
internal fun RunSkip(by: Int, size: Dp, onTouch: () -> Unit = {}) {
    val run = LocalViewerRun.current ?: return
    val can = if (by < 0) run.hasPrevious else run.hasNext
    val label = if (by < 0) "Previous video" else "Next video"
    SkipDisc(
        size,
        label,
        enabled = can,
        onClick = { run.step(by); onTouch() },
    ) {
        Icon(if (by < 0) FiletIcons.Prev else FiletIcons.Next, label, tint = Color.White, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun SkipDisc(
    size: Dp,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
