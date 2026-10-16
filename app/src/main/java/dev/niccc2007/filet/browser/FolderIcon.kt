package dev.niccc2007.filet.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder, drawn as a thing with things in it.
 *
 * ## What changed and why
 *
 * It was a hollow outline, identical for every folder in the app. Two problems with that, and
 * the second is the one that was reported: an outline at 30dp is four thin strokes and reads
 * as a diagram rather than an object, and every folder looking the same is what made long
 * lists feel uniform and lifeless.
 *
 * So it is solid, and it carries its own contents: up to three of the pictures inside it,
 * fanned behind the front panel like paper in a wallet. A folder of photographs now looks
 * like a folder of photographs. One with nothing to show still reads as a folder, because the
 * shape does that work rather than the contents.
 *
 * ## How it is built, back to front
 *
 * 1. the **back panel**, with the tab - one path, filled, in the folder's own colour
 * 2. the **sheets** - the previews, rotated a few degrees either side so they fan
 * 3. the **front panel** - solid, slightly lighter, covering the lower two thirds
 *
 * The front panel is what makes the sheets look contained rather than stacked on top, and it
 * is also why the fan is small: the sheets only ever show their top edge, so a wide fan pushes
 * the outer two out of the folder entirely.
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
    val colors = Filet.colors
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val wantsPreviews = previews && size >= PREVIEW_FROM && !inspecting

    val peek = remember(context) {
        runCatching { FiletApp.graphOf(context).folderPeek }.getOrNull()
    }
    val children = if (peek == null || !wantsPreviews) {
        emptyList()
    } else {
        rememberFolderPeek(peek, node.path, node.mtime, true).value
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            // A folder is wider than it is tall and sits on the baseline rather than filling
            // the box, or next to a square thumbnail it looks oversized.
            val top = h * 0.20f
            val bottom = h * 0.90f
            val left = w * 0.06f
            val right = w * 0.94f
            val r = CornerRadius(w * 0.09f, w * 0.09f)

            // The back, with the tab. Drawn as one rounded body plus a tab that rises from
            // the left - a separate tab shape leaves a seam where the two meet at this size.
            val back = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(left, top + h * 0.06f, right, bottom),
                        topLeft = r, topRight = r, bottomRight = r, bottomLeft = r,
                    ),
                )
                addRoundRect(
                    RoundRect(
                        Rect(left, top, left + w * 0.40f, top + h * 0.22f),
                        topLeft = r, topRight = r,
                        bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(
                back,
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.78f)),
                    startY = top,
                    endY = bottom,
                ),
            )
        }

        // The sheets. Only their top edge shows, so they are placed high and the front panel
        // is drawn over the rest of them.
        if (children.isNotEmpty()) {
            for ((i, child) in children.withIndex()) {
                val lean = (i - (children.size - 1) / 2f) * FAN
                Sheet(
                    node = child,
                    size = size,
                    lean = lean,
                    context = context,
                )
            }
        }

        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val left = w * 0.06f
            val right = w * 0.94f
            val r = CornerRadius(w * 0.10f, w * 0.10f)
            // The front. Lighter than the back, and opaque - this is the panel that makes the
            // sheets read as being INSIDE rather than piled on.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(lighten(tint, 0.18f), tint),
                    startY = h * 0.42f,
                    endY = h * 0.90f,
                ),
                topLeft = Offset(left, h * 0.42f),
                size = Size(right - left, h * 0.48f),
                cornerRadius = r,
            )
            // A hairline along the top of the front panel, which is what separates it from
            // the sheets when the folder's colour and a photograph happen to be close.
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(left + w * 0.04f, h * 0.43f),
                end = Offset(right - w * 0.04f, h * 0.43f),
                strokeWidth = h * 0.012f,
            )
        }
    }
}

/** One preview, peeking out of the top of the folder. */
@Composable
private fun Sheet(node: VNode, size: Dp, lean: Float, context: android.content.Context) {
    val colors = Filet.colors
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
            .size(width = size * 0.58f, height = size * 0.46f)
            // Sheets sit above the front panel, spread by how far they lean.
            .offset(x = size * (lean / FAN) * 0.16f, y = -size * 0.13f)
            .rotate(lean)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(size.value.dp * 0.06f)),
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = size * 0.58f, height = size * 0.46f),
        )
    }
}

/** Toward white, for the front panel. A second theme colour would not track a tinted folder. */
private fun lighten(c: Color, by: Float) = Color(
    red = c.red + (1f - c.red) * by,
    green = c.green + (1f - c.green) * by,
    blue = c.blue + (1f - c.blue) * by,
    alpha = c.alpha,
)

/** How far apart the sheets fan, in degrees. */
private const val FAN = 9f

/**
 * Below this a preview is a few pixels and says nothing; the plain folder is better.
 *
 * Eighteen, not twenty-six. The list steps give the icon slot 16 to 21dp - the file
 * thumbnails beside it live in the same slot - so a threshold above that turned previews off
 * in every view anybody actually uses, which is why a folder of three hundred photographs
 * showed none of them.
 */
private val PREVIEW_FROM = 18.dp
