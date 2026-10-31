package dev.niccc2007.filet.docs

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A code block or a table sized by hand, from the "⤡ Resize" chip on its pad. Pure.
 *
 * One drag sets both directions at once, the way a window's corner does: across for the width,
 * down for the height, so the block follows the finger whichever way it goes. Two grips, one
 * per edge, would put a second target on a phone-sized pad for what one corner already does.
 *
 * The width never passes the room the page gives the block. A block dragged wider than the
 * screen takes its own grip off the edge, and then nothing can bring it back. The height never
 * passes what the content needs: a box taller than its content is empty space, and a drag that
 * had overshot would then have to travel back through that dead distance before anything moved.
 */
internal object BlockResize {

    /** Narrower than this and a code line or a column is a letter wide. In dp. */
    const val MIN_W = 120f

    /** Shorter than this and the block is its pad and nothing else. In dp. */
    const val MIN_H = 48f

    /**
     * Where a drag of [dx] by [dy] takes a block that was [startW] by [startH] when it began, in
     * dp, held inside [maxW] and [maxH]. A room smaller than the minimum wins over the minimum.
     */
    fun next(startW: Float, startH: Float, dx: Float, dy: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val w = (startW + dx).coerceIn(minOf(MIN_W, maxW), maxW)
        val h = (startH + dy).coerceIn(minOf(MIN_H, maxH), maxH)
        return w to h
    }
}

/**
 * One block's hand-set size, in dp. Null means the block's own: the page's width, and its usual
 * height cap.
 *
 * What the block last measured at is kept beside it, because a drag starts from the size on
 * screen rather than from the stored one - the first drag of a block nobody has sized yet has
 * no stored size to start from.
 */
internal class BlockSize {
    var width by mutableStateOf<Float?>(null)
    var height by mutableStateOf<Float?>(null)

    /** As drawn, in dp. */
    var shownW = 0f
    var shownH = 0f

    /**
     * How much of the content is below the box, in dp, read when a drag starts. A function and
     * not a number filed on layout: Wrap changes how tall the content is without changing the
     * box, so a number filed when the box last changed size would be stale.
     */
    var overflow: () -> Float = { 0f }

    val resized: Boolean get() = width != null || height != null

    fun reset() {
        width = null
        height = null
    }
}

/**
 * The grip: a chip that is dragged rather than tapped. A tap puts the block back to its own size.
 *
 * @param maxW the room the page gives the block, in dp.
 */
@Composable
internal fun ResizeChip(size: BlockSize, maxW: Float) {
    val colors = Filet.colors
    val density = LocalDensity.current.density
    // Read through state: the drag handler below lives as long as the block, and a room or
    // density captured when it started would hold a rotated screen to the old width.
    val room by rememberUpdatedState(maxW)
    val scale by rememberUpdatedState(density)
    var dragging by remember { mutableStateOf(false) }
    val lit = dragging || size.resized
    Text(
        "⤡ Resize",
        fontSize = 10.5.sp,
        fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium,
        color = if (lit) colors.accent else colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (lit) colors.accent.copy(alpha = 0.55f) else colors.fg3.copy(alpha = 0.28f), RoundedCornerShape(50))
            .pointerInput(size) {
                var startW = 0f
                var startH = 0f
                var reach = 0f
                var moved = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        startW = size.shownW
                        startH = size.shownH
                        reach = startH + size.overflow()
                        moved = Offset.Zero
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, delta ->
                    change.consume()
                    moved += delta
                    val (w, h) = BlockResize.next(
                        startW, startH, moved.x / scale, moved.y / scale,
                        maxW = room, maxH = reach,
                    )
                    size.width = w
                    size.height = h
                }
            }
            .pointerInput(size) { detectTapGestures(onTap = { size.reset() }) }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
    Spacer(Modifier.width(4.dp))
}
