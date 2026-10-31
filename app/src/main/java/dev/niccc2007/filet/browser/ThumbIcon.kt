package dev.niccc2007.filet.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.delay

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
    // Marked here rather than at the call sites, the same argument the folder icon makes a few
    // lines down: a pick can be answered from the folder list, search results, Home, a
    // category, the gallery, the history screen and a sheet's own header, and every one of
    // them draws its file through this. There are eight, and an eighth was going to be missed.
    //
    // Bug identified here: a tap on a photo in the Photos tab chose the file and nothing on
    // screen changed, because the only highlight in the app was a pane's selection background
    // and that tab has no selection. A tap that does not look like it did anything reads as a
    // broken tab, which is how it was reported - and then "Use these" naming a count the row
    // had never shown confirmed it.
    if (isChosenForPick(node.path)) {
        // No size on the Box, so it takes the thumbnail's own and the layout is byte-for-byte
        // what it was unchosen. The tick is held under half the slot for the same reason.
        Box(contentAlignment = Alignment.BottomEnd) {
            Thumb(node, size, fallbackTint, modifier, enabled, glyphSize)
            ChosenTick((size * 0.5f).coerceIn(14.dp, 22.dp).coerceAtMost(size * 0.6f))
        }
        return
    }
    Thumb(node, size, fallbackTint, modifier, enabled, glyphSize)
}

/**
 * The mark on a file the live pick has taken.
 *
 * A filled disc with a ring around it, because this lands on top of photographs: a bare tick,
 * or a disc with no ring, disappears into a bright picture at exactly the moment it is the only
 * thing saying the tap worked.
 *
 * The ring is the surface colour rather than white. White is invisible against the thing it has
 * to be seen against half the time - the first photo this was tried on was a screenshot of a
 * receipt, which is a white rectangle, and the badge read as nothing at all.
 */
@Composable
private fun ChosenTick(side: Dp) {
    Box(
        Modifier
            .padding(2.dp)
            .size(side)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Filet.colors.high)
            .padding(1.5.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Filet.colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FiletIcons.Check,
            "Chosen",
            tint = Color.White,
            modifier = Modifier.size(side * 0.62f),
        )
    }
}

@Composable
private fun Thumb(
    node: VNode,
    size: Dp,
    fallbackTint: Color,
    modifier: Modifier,
    enabled: Boolean,
    glyphSize: Dp?,
) {
    val kind = FileKind.of(node)
    val previewable = enabled && !node.isDir && Thumbnails.canPreview(node.extension)
    val glyph = glyphSize ?: size

    // A folder is drawn rather than lettered, and it carries what is inside it. Here rather
    // than at the call sites so every list, tile, feed row and picker gets it at once - there
    // are nine of them and a tenth would have been forgotten.
    if (node.isDir) {
        FolderIcon(
            node = node,
            // The SLOT, not the glyph.
            //
            // `glyphSize` exists because a 30dp tinted square holding a 15dp glyph needs two
            // numbers, and a folder is not a glyph - it is an object that fills its slot the
            // way a thumbnail does. Drawn at the glyph size it came out half the size of
            // every picture beside it AND under the threshold where previews are worth
            // drawing, so a folder of three hundred photographs showed none of them.
            size = size,
            tint = fallbackTint,
            modifier = modifier,
            previews = enabled,
        )
        return
    }

    if (!previewable) {
        Fallback(node, kind, glyph, fallbackTint, modifier)
        return
    }

    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    // Decode a little larger than the slot so the image stays crisp on a high-density screen
    // without asking for the full picture.
    val px = (size.value * 2.5f).toInt().coerceIn(48, 320)

    // An answer we already hold is used on the spot: no coroutine, no delay, no work staged.
    val known = remember(node.path, node.mtime, node.size, px) {
        Thumbnails.peek(node.path, node.size, node.mtime, px)
    }
    var bitmap by remember(node.path, node.mtime, node.size, px) {
        mutableStateOf(known?.bitmap)
    }

    // Only when nobody has looked yet. A remembered failure stays a failure and must not be
    // retried once per row per scroll.
    if (known == null) {
        LaunchedEffect(node.path, node.mtime, node.size, px) {
            if (inspecting) return@LaunchedEffect

            // Bug identified here: a decode was staged for EVERY row entering composition, so a
            // fling through a folder of two thousand pictures queued two thousand decodes. The
            // effect is cancelled when the row leaves the screen, but `Thumbnails.get` is a
            // BLOCKING decode with no suspension point in it - so cancelling stopped the result
            // being used and did not stop the work. The IO pool stayed saturated for seconds
            // after the finger left the glass, starving the main thread behind it. That is the
            // scroll lag, and it was worst exactly where it was reported: flinging fast.
            //
            // A row still on screen after this delay is a row somebody is looking at. One that
            // is not now costs nothing whatsoever, because the delay is the only thing that ran.
            delay(SETTLE_MS)
            val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@LaunchedEffect
            bitmap = runCatching {
                Thumbnails.load(context, graph.vfs, node.path, node.size, node.mtime, px)
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

/**
 * How long a row must stay on screen before its preview is worth decoding.
 *
 * Long enough that a fling pays for nothing it scrolls past, short enough that it is invisible
 * once the list settles - a thumbnail takes tens of milliseconds to decode anyway, so this is
 * well inside the time the slot was going to hold a glyph regardless.
 */
private const val SETTLE_MS = 90L
