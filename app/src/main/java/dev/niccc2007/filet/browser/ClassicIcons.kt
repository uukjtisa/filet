package dev.niccc2007.filet.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode

/**
 * The folder and file icons from before the drawn art, kept for [dev.niccc2007.filet.icons.IconStyle.PLAIN].
 *
 * Unchanged in look from what they were: a folder in one tint with up to three of its pictures
 * standing in it, and a file as its kind's glyph, or a monogram of its extension when the kind
 * has nothing better. Only the reads moved to today's peek and thumbnail calls.
 */

/** A folder in [tint], with up to three of its pictures standing between its back and front. */
@Composable
fun ClassicFolderIcon(
    node: VNode,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    previews: Boolean = true,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    // A sample folder has nothing on a drive to peek at. See LocalSampleInside.
    val wants = previews && size >= CLASSIC_PREVIEW_FROM && !inspecting && LocalSampleInside.current == null
    val peek = remember(context) { if (inspecting) null else runCatching { FiletApp.graphOf(context).folderPeek }.getOrNull() }
    val children = if (peek == null || !wants) {
        emptyList()
    } else {
        rememberFolderPeek(peek, node.path, node.mtime, true).value.items
            .filter { it.kind == PeekKind.MEDIA }
            .map { it.node }
            .take(3)
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawClassicBack(tint) }
        for ((i, child) in children.withIndex()) {
            val lean = (i - (children.size - 1) / 2f) * FAN
            ClassicSheet(child, size, lean)
        }
        Canvas(Modifier.size(size)) { drawClassicFront(tint) }
    }
}

/** A file with no picture: its kind's glyph in [tint], or its extension's monogram. */
@Composable
fun ClassicFileGlyph(node: VNode, glyph: Dp, tint: Color, modifier: Modifier = Modifier) {
    val kind = FileKind.of(node)
    val token = if (kind == FileKind.OTHER && !node.isDir) Monogram.of(node.extension) else null
    if (token == null) {
        Icon(kind.icon, null, tint = tint, modifier = modifier.size(glyph))
        return
    }
    Box(modifier.size(glyph), contentAlignment = Alignment.Center) {
        Text(
            token,
            // 30dp is the size the sp figures were chosen against, so a bigger slot scales up.
            fontSize = (Monogram.sizeSp(token) * (glyph.value / 30f).coerceIn(0.8f, 2.2f)).sp,
            color = tint,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
        )
    }
}

/** The panel the contents stand against, and the tab with its sloped fold. */
private fun DrawScope.drawClassicBack(tint: Color) {
    val w = size.width
    val h = size.height
    val left = w * 0.05f
    val right = w * 0.95f
    val tabTop = h * 0.20f
    val bodyTop = h * 0.33f
    val bottom = h * 0.88f
    val r = w * 0.07f
    val back = Path().apply {
        moveTo(left, bodyTop)
        quadraticBezierTo(left, tabTop, left + r, tabTop)
        lineTo(w * 0.36f, tabTop)
        lineTo(w * 0.50f, bodyTop)
        lineTo(right - r, bodyTop)
        quadraticBezierTo(right, bodyTop, right, bodyTop + r)
        lineTo(right, bottom - r)
        quadraticBezierTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadraticBezierTo(left, bottom, left, bottom - r)
        close()
    }
    drawPath(back, Brush.verticalGradient(listOf(lighten(tint, 0.10f), tint), startY = tabTop, endY = bottom))
}

/** The front: a trapezoid, wider at the top, overlapping the back. */
private fun DrawScope.drawClassicFront(tint: Color) {
    val w = size.width
    val h = size.height
    val top = h * 0.44f
    val bottom = h * 0.90f
    val topLeft = w * 0.02f
    val topRight = w * 0.98f
    val footLeft = w * 0.09f
    val footRight = w * 0.91f
    val r = w * 0.07f
    val front = Path().apply {
        moveTo(topLeft + r, top)
        lineTo(topRight - r, top)
        quadraticBezierTo(topRight, top, topRight - r * 0.5f, top + r)
        lineTo(footRight, bottom - r)
        quadraticBezierTo(footRight, bottom, footRight - r, bottom)
        lineTo(footLeft + r, bottom)
        quadraticBezierTo(footLeft, bottom, footLeft, bottom - r)
        lineTo(topLeft + r * 0.5f, top + r)
        quadraticBezierTo(topLeft, top, topLeft + r, top)
        close()
    }
    drawPath(front, Brush.verticalGradient(listOf(lighten(tint, 0.28f), lighten(tint, 0.02f)), startY = top, endY = bottom))
    drawLine(
        color = Color.White.copy(alpha = 0.26f),
        start = Offset(topLeft + r, top + h * 0.014f),
        end = Offset(topRight - r, top + h * 0.014f),
        strokeWidth = h * 0.016f,
    )
}

/** One picture, standing in the folder. */
@Composable
private fun ClassicSheet(node: VNode, size: Dp, lean: Float) {
    val context = LocalContext.current
    var bitmap by remember(node.path, node.mtime) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(node.path, node.mtime) {
        val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
        val px = (size.value * 2f).toInt().coerceIn(32, 160)
        bitmap = runCatching { Thumbnails.load(context, graph.vfs, node.path, node.size, node.mtime, px) }.getOrNull()
    }
    val image = bitmap ?: return
    Box(
        Modifier
            .offset(x = size * (lean / FAN) * 0.09f, y = -size * 0.10f)
            .rotate(lean)
            .size(width = size * 0.54f, height = size * 0.42f)
            .clip(RoundedCornerShape(size * 0.05f)),
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = size * 0.54f, height = size * 0.42f),
        )
    }
}

/** Toward white, so a tinted folder keeps its own hue. */
private fun lighten(c: Color, by: Float) = Color(
    red = c.red + (1f - c.red) * by,
    green = c.green + (1f - c.green) * by,
    blue = c.blue + (1f - c.blue) * by,
    alpha = c.alpha,
)

/** How far apart the contents lean, in degrees. */
private const val FAN = 5f

/** Below this a picture is a few pixels and says nothing. */
private val CLASSIC_PREVIEW_FROM = 22.dp
