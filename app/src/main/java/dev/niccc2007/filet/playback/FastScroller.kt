package dev.niccc2007.filet.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet
import kotlin.math.roundToInt

/**
 * A fast scroller for a long list, with a bubble that says where you are going.
 *
 * ## Why a list this long needs one
 *
 * All Music is capped at two thousand rows. A flick moves about a screen; reaching the middle
 * is twenty flicks and reaching a particular letter is luck. The system scrollbar is a position
 * indicator, not a control - it says how far down you are and cannot be grabbed.
 *
 * ## Why the bubble only exists while a thumb is down
 *
 * Because at rest it is the answer to a question nobody is asking, floating over the names
 * somebody is reading. While dragging it IS the navigation. That is also what lets it be a
 * spelled-out `Oct 8 2026` rather than a cramped `2/3/26`: it never competes with a row for
 * room. What it says is decided by the list's ORDERING - see [MusicScrollLabel].
 *
 * ## Three faults the first cut had, all of them here
 *
 * **It jerked to the ends.** The track's height was read inside the `pointerInput` block, so
 * the first gesture divided by a height of zero, every drag resolved to 0 or 1, and the list
 * snapped to top or bottom. It is measured with `onSizeChanged` now, which has run before any
 * touch can arrive.
 *
 * **The bubble never appeared.** It was nested inside the touch strip, which is a couple of
 * dozen dp wide, so a label several times that wide was laid out clipped to nothing. The strip
 * is now wide enough to hold the bubble, with the rail pinned to its trailing edge.
 *
 * **It was too faint to find.** A 3dp hairline is a scrollbar; this is a control, so it has a
 * grabbable thumb with a grip on it that thickens while held.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyListState,
    itemCount: Int,
    /** The bubble's text for a row index, or null to show no bubble for it. */
    labelAt: (Int) -> String?,
    /**
     * How many list items sit ABOVE the rows this scroller addresses - headers, a selection bar.
     *
     * Bug identified here: a row index was handed straight to `scrollToItem`, which addresses
     * the LAZY LIST and counts those headers, so every drag landed as many rows short as there
     * were items above the tracks. A comment above the call site claimed the offset was handled;
     * nothing in the code did it. Taking it as a parameter makes the caller state it, and
     * neither side has to remember.
     */
    indexOffset: Int = 0,
    modifier: Modifier = Modifier,
) {
    // Below this a flick reaches everything and a scrubber is a control in the way.
    if (itemCount < MIN_ROWS) return

    val colors = Filet.colors
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    // Measured by layout, NOT inside the gesture block. See the note above.
    var trackPx by remember { mutableIntStateOf(0) }
    var held by remember { mutableFloatStateOf(0f) }

    // The list's own position, as a fraction. Through `derivedStateOf` so this redraws when the
    // first visible item changes rather than on every pixel of a fling.
    val listFraction by remember(itemCount, indexOffset) {
        derivedStateOf {
            val span = (itemCount - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
            val row = (state.firstVisibleItemIndex - indexOffset).coerceAtLeast(0)
            (row.toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) held else listFraction
    val rowAt = (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)

    // Driven straight off the drag - an animated scroll would lag the thumb and the bubble
    // would be naming a row the list has not reached.
    //
    // ONE coroutine for the whole gesture, fed by a conflated flow, rather than one per row
    // crossed. Keyed on `rowAt`, this effect was torn down and relaunched for every row the
    // finger passed over - two thousand times in a single sweep of All Music - and each launch
    // asked for a full layout pass whether or not a frame was going to be drawn for it. That is
    // why dragging the scroller felt heavier than flinging the list.
    //
    // `snapshotFlow` conflates, so the list is told to scroll at most once per frame, to the row
    // the finger is actually on by then. The row is computed INSIDE the flow block so the drag
    // state is read under snapshot observation; computing it outside would capture one value and
    // never see another.
    LaunchedEffect(dragging, itemCount, indexOffset) {
        if (!dragging) return@LaunchedEffect
        snapshotFlow { (held * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1) }
            .collect { runCatching { state.scrollToItem(it + indexOffset) } }
    }

    val thumbPx = with(density) { THUMB.toPx() }
    val travel = (trackPx - thumbPx).coerceAtLeast(1f)

    // Up while the list moves and for a moment after, and otherwise not there at all - for touch
    // as much as for the eye.
    //
    // Bug identified: the touch strip covered the whole trailing edge at all times, on top of the
    // rows, and the trailing edge of a track row is its menu button. Compose gives a press to the
    // topmost sibling it lands on, so every row's menu in a long list went dead - reported as the
    // dots on the track cards no longer working. A scroller that only exists once the list is
    // moving is the platform's own answer: at rest the edge belongs to the rows again.
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { moving ->
            if (moving) {
                awake = true
            } else {
                kotlinx.coroutines.delay(IDLE_MS)
                awake = false
            }
        }
    }
    val shown = awake || dragging
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    if (alpha == 0f && !shown) return
    val startAt = androidx.compose.runtime.rememberUpdatedState(listFraction)

    Box(
        modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            // Wide enough for the bubble to live in, with the rail at the trailing edge. The
            // bubble is a child of this box, so this width is what stopped it being drawn.
            .width(STRIP)
            .padding(vertical = 8.dp)
            .onSizeChanged { trackPx = it.height }
            .graphicsLayer { this.alpha = alpha },
    ) {
        // The rail, at the trailing edge. Quiet at rest - it is an indicator then.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RAIL)
                .clip(RoundedCornerShape(50))
                .background(colors.lineSoft),
        )

        // The thumb. A pill with a grip, because this is a thing to grab rather than a mark
        // showing where you are.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * travel).roundToInt()) }
                .height(THUMB)
                .width(if (dragging) THUMB_W_HELD else THUMB_W)
                .shadow(if (dragging) 6.dp else 2.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                // No gesture of its own: the touch strip below covers the whole edge, so a
                // drag anywhere along it takes the thumb. A 10dp target that has to be hit
                // exactly is a control that feels broken on a phone.
                .background(if (dragging) colors.accent else colors.fg3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FiletIcons.Sort,
                null,
                tint = if (dragging) MaterialTheme.colorScheme.onPrimary else colors.high,
                modifier = Modifier.height(13.dp).width(13.dp),
            )
        }

        // The touch area: the thumb and a margin round it, only while the scroller is up. Not
        // the whole edge any more - see `awake` - so even while it is showing, the rows' menus
        // above and below the thumb still answer.
        if (shown) Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (fraction * travel - with(density) { GRAB.toPx() }).roundToInt()) }
                .height(THUMB + GRAB * 2)
                .width(TOUCH)
                .pointerInput(itemCount, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            // From where the thumb already is: the press landed on it, so the
                            // first frame of the drag must not move it.
                            held = startAt.value
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        change.consume()
                        held = (held + dragAmount / travel).coerceIn(0f, 1f)
                    }
                },
        )

        val label = if (dragging) labelAt(rowAt) else null
        AnimatedVisibility(
            visible = label != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        0,
                        (fraction * travel + thumbPx / 2f - with(density) { BUBBLE_HALF.toPx() })
                            .roundToInt(),
                    )
                },
        ) {
            Row(
                Modifier
                    .shadow(10.dp, RoundedCornerShape(11.dp))
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.accent)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    label.orEmpty(),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Under this many rows a flick reaches everything and a scrubber is just in the way. */
private const val MIN_ROWS = 60

/**
 * The whole strip's width.
 *
 * It has to hold the bubble, because the bubble is a child of it - a strip sized to the rail
 * alone is what laid the label out clipped to nothing.
 */
private val STRIP = 168.dp

/** The part that takes the touch, at the trailing edge. */
private val TOUCH = 34.dp

/** How far past the thumb's ends a press still takes it. */
private val GRAB = 16.dp

/** How long the scroller stays up after the list stops. */
private const val IDLE_MS = 1_500L

private val RAIL = 3.dp
private val THUMB = 52.dp
private val THUMB_W = 10.dp
private val THUMB_W_HELD = 14.dp

/** Half the bubble's height, to centre it on the thumb. */
private val BUBBLE_HALF = 15.dp
