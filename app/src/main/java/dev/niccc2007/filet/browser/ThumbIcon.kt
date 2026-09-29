package dev.niccc2007.filet.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A row or tile's leading image: the file's own preview when it has one, its glyph otherwise.
 *
 * The glyph is drawn **first and always**, and the preview replaces it if and when one
 * arrives. That ordering is the whole trick: a list scrolls at sixty frames a second and a
 * thumbnail takes tens of milliseconds to decode, so anything that waits for the image before
 * drawing anything produces a list of grey holes.
 *
 * Decoding happens off the main thread, keyed on the node's identity, and
 * [Thumbnails] remembers both successes and failures - so scrolling back up costs nothing and
 * a file with no preview is attempted once per session, not once per frame.
 */
@Composable
fun FileThumb(
    node: VNode,
    size: Dp,
    fallbackTint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * How big the glyph is when there is no preview.
     *
     * A thumbnail fills its slot; a glyph in the same slot at the same size is a huge icon. In
     * the redesigned rows the slot is a 30dp tinted square holding a 15dp glyph, so the two
     * sizes genuinely differ and one parameter cannot describe both. Null keeps them equal,
     * which is what every existing caller wants.
     */
    glyphSize: Dp? = null,
) {
    val kind = FileKind.of(node)
    val previewable = enabled && !node.isDir && Thumbnails.canPreview(node.extension)
    val glyph = glyphSize ?: size

    if (!previewable) {
        Fallback(node, kind, glyph, fallbackTint, modifier)
        return
    }

    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    var bitmap by remember(node.path, node.mtime, node.size) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(node.path, node.mtime, node.size, size) {
        if (inspecting) return@LaunchedEffect
        val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
        // Decode a little larger than the slot so the image stays crisp on a high-density
        // screen without asking for the full picture.
        val px = (size.value * 2.5f).toInt().coerceIn(48, 320)
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                Thumbnails.get(context, graph.vfs, node.path, node.size, node.mtime, px)
            }.getOrNull()
        }
    }

    val image = bitmap
    if (image == null) {
        Fallback(node, kind, glyph, fallbackTint, modifier)
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size.value.coerceAtMost(10f).dp * 0.28f))
                .background(Filet.colors.high),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * What fills the slot when there is no picture: the kind's glyph, or the extension when the kind
 * has no glyph of its own.
 *
 * Only for [FileKind.OTHER]. Every other kind already has a drawing that says more at this size
 * than three letters would - see [Monogram] for the whole argument, including why an extension
 * too long to fit gets nothing rather than getting cut.
 *
 * The one piece of layout worth noting: the token is drawn at [Monogram]'s size scaled to the
 * slot, so a 15dp glyph slot and a 44dp header thumbnail both hold readable text rather than one
 * of them holding a smudge.
 */
@Composable
private fun Fallback(
    node: VNode,
    kind: FileKind,
    glyph: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val token = if (kind == FileKind.OTHER && !node.isDir) Monogram.of(node.extension) else null
    if (token == null) {
        Icon(kind.icon, null, tint = tint, modifier = modifier.size(glyph))
        return
    }
    Box(modifier.size(glyph), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            token,
            // 30dp is the size the sp figures were chosen against, so a bigger slot scales up.
            fontSize = (Monogram.sizeSp(token) * (glyph.value / 30f).coerceIn(0.8f, 2.2f)).sp,
            color = tint,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
        )
    }
}
