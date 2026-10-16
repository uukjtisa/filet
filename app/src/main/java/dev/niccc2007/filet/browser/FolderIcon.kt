package dev.niccc2007.filet.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder, drawn as a thing with things in it.
 *
 * ## The shape, and why each part of it is there
 *
 * The first version was a rounded rectangle with a notch cut out of the top left, and it read
 * as a rectangle with a notch. Held against the folder on a desktop, the difference is not the
 * colour or the corner radius. It is three things:
 *
 *  1. **The tab's right edge slopes.** It is the fold of the card. Every folder icon anybody
 *     recognises has that diagonal, and squaring it off is most of what made ours generic.
 *  2. **The front is a trapezoid, not a rectangle.** Its top edge is wider than its foot,
 *     which is what an open folder leaning towards you looks like. Parallel sides read flat.
 *  3. **The front overlaps the back and the contents sit BETWEEN them.** That is what makes
 *     the pictures look contained rather than piled on top of a shape.
 *
 * ## The contents
 *
 * Up to three of the pictures inside, standing in the folder, centred and barely leaning. The
 * first attempt fanned them hard and off to one side, which read as paper falling out of it.
 * A folder shows one or two things standing in it; that is all it takes to say what is in
 * there, and anything more fights the shape.
 */
@Composable
fun FolderIcon(
    node: VNode,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    /** Off in the compact steps, where there is no room for a preview to read as anything. */
    previews: Boolean = true,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val wantsPreviews = previews && size >= PREVIEW_FROM && !inspecting

    val peek = remember(context) {
        runCatching { FiletApp.graphOf(context).folderPeek }.getOrNull()
    }
    val children = if (peek == null || !wantsPreviews) {
        emptyList()
    } else {
        rememberFolderPeek(peek, node.path, node.mtime, true).value.previews
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) { drawBack(tint) }
        for ((i, child) in children.withIndex()) {
            val lean = (i - (children.size - 1) / 2f) * FAN
            Sheet(node = child, size = size, lean = lean, context = context)
        }
        Canvas(Modifier.size(size)) { drawFront(tint) }
    }
}

/** The panel the contents stand against, and the tab with its sloped fold. */
private fun DrawScope.drawBack(tint: Color) {
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
        // The fold: the tab's right edge runs down on the diagonal into the body.
        lineTo(w * 0.50f, bodyTop)
        lineTo(right - r, bodyTop)
        quadraticBezierTo(right, bodyTop, right, bodyTop + r)
        lineTo(right, bottom - r)
        quadraticBezierTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadraticBezierTo(left, bottom, left, bottom - r)
        close()
    }
    drawPath(
        back,
        Brush.verticalGradient(
            listOf(lighten(tint, 0.10f), tint),
            startY = tabTop,
            endY = bottom,
        ),
    )
}

/**
 * The front: a trapezoid, wider at the top, overlapping the back.
 *
 * Parallel sides make a folder look like two stacked rectangles. The taper is small - seven
 * percent of the width each side - which is enough for the eye to read depth and little
 * enough that it never looks like a bucket.
 */
private fun DrawScope.drawFront(tint: Color) {
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
    drawPath(
        front,
        Brush.verticalGradient(
            listOf(lighten(tint, 0.28f), lighten(tint, 0.02f)),
            startY = top,
            endY = bottom,
        ),
    )
    // The lip along the top of the front panel: the edge of the card catching the light, and
    // what separates it from a photograph standing directly behind it.
    drawLine(
        color = Color.White.copy(alpha = 0.26f),
        start = Offset(topLeft + r, top + h * 0.014f),
        end = Offset(topRight - r, top + h * 0.014f),
        strokeWidth = h * 0.016f,
    )
}

/** One preview, standing in the folder. */
@Composable
private fun Sheet(node: VNode, size: Dp, lean: Float, context: android.content.Context) {
    var bitmap by remember(node.path, node.mtime) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(node.path, node.mtime) {
        val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
        val px = (size.value * 2f).toInt().coerceIn(32, 160)
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                Thumbnails.get(context, graph.vfs, node.path, node.size, node.mtime, px)
            }.getOrNull()
        }
    }
    val image = bitmap ?: return
    Box(
        Modifier
            // Centred, and only just off the middle, so the stack stands in the folder rather
            // than spilling out of one side of it.
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

/** Toward white. A second theme colour would not track a folder that has been tinted. */
private fun lighten(c: Color, by: Float) = Color(
    red = c.red + (1f - c.red) * by,
    green = c.green + (1f - c.green) * by,
    blue = c.blue + (1f - c.blue) * by,
    alpha = c.alpha,
)

/** How far apart the contents lean, in degrees. Small: they are standing, not falling. */
private const val FAN = 5f

/**
 * Below this a preview is a few pixels and says nothing; the plain folder is better.
 *
 * Eighteen, not twenty-six. The list steps give the icon slot 16 to 21dp - the file
 * thumbnails beside it live in that same slot - so a floor above that turned previews off in
 * every view anybody actually uses, and a folder of three hundred photographs showed none.
 */
private val PREVIEW_FROM = 18.dp
