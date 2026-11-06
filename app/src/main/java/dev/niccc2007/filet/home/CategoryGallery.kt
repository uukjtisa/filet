package dev.niccc2007.filet.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileThumb
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.ui.WarmAhead
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A category as tiles of the files' own pictures, still in days. [CategoryLayout.GALLERY].
 *
 * The days stay. A gallery that drops them is a different reading - "everything, as a wall" -
 * and the day headers are what lets a gallery of three thousand photos be folded down to the
 * week somebody is looking for, the same way the list folds.
 *
 * ## Exact tiles, so the warm-up decodes the picture that is drawn
 *
 * The column count is worked out from the width here rather than left to `GridCells.Adaptive`,
 * because the tile size has to be KNOWN: [Thumbnails] keys a decode on its pixel size, and the
 * rows warmed ahead of the screen must ask for exactly the size the tile will ask for, or every
 * warm-up is a decode nobody uses. One image pipeline - [FileThumb] on screen, [WarmAhead]
 * through `Thumbnails.load(ahead = true)` off it - and no second one.
 *
 * ## One touch handler, on the grid, not one per tile
 *
 * A tap opens, or toggles while selecting. A hold lifts the tile into its peek, or with a
 * selection running extends to it ([ListPick]). A slide after the hold selects the run the finger
 * crosses, and scrolls when it reaches the top or bottom edge, so a month of photos is one stroke.
 *
 * That last part is why the handler is the grid's. A tile's own handler dies with the tile, and
 * a slide that scrolls is exactly the gesture that carries its first tile off the screen and out
 * of the composition - the selection would stop mid-stroke. So the tiles take no touches of their
 * own; the grid finds the tile under the finger from its layout and decides.
 */
@Composable
internal fun CategoryGallery(
    vm: BrowserViewModel,
    category: MediaCategory,
    groups: List<DayGroup>,
    collapsed: Set<String>,
    header: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    onFold: (DayGroup) -> Unit,
    pick: ListPick,
    /** Lift a held tile into its peek. */
    onPeek: (VNode) -> Unit,
    /** Put the peek away: the finger moved after the hold, which makes it a slide. */
    onUnpeek: () -> Unit,
) {
    val context = LocalContext.current
    // A cover with its title under it for audio, since two covers from one album are the same
    // picture; a bare picture for photos and videos, where the picture IS the file.
    val captioned = category == MediaCategory.AUDIO
    val cells = remember(groups, collapsed) { GalleryCells.of(groups, collapsed) }
    val selecting = pick.running()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val slide = remember { Slide() }
    // Cancelled with the screen: an edge scroll left running would scroll a grid nobody holds.
    DisposableEffect(slide) { onDispose { slide.auto?.cancel() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = GalleryCells.grid(maxWidth, captioned)
        val px = GalleryCells.decodePx(grid.tile)
        val state = dev.niccc2007.filet.browser.rememberKeptGridState("category:" + category.id)
        val density = LocalDensity.current
        val edgePx = with(density) { GalleryCells.EDGE.roundToPx() }
        val zonePx = with(density) { SLIDE_EDGE_ZONE.toPx() }
        val stepPx = with(density) { SLIDE_EDGE_STEP.toPx() }
        val heightPx = constraints.maxHeight.toFloat()

        /**
         * The tile under [at], a point in the grid's own space. The layout reports each item
         * WITHOUT the content padding the grid places it by (`LazyGridMeasuredItem.place` adds
         * it on), so the side padding is taken off the point rather than added to every item.
         * A point in the gap between two tiles is no tile, and the run keeps its last end.
         */
        fun tileAt(at: Offset): GalleryCell.Tile? {
            val x = (at.x - edgePx).toInt()
            val y = at.y.toInt()
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull {
                x >= it.offset.x && x < it.offset.x + it.size.width &&
                    y >= it.offset.y && y < it.offset.y + it.size.height
            } ?: return null
            // The header is item 0, so a cell's item sits one along. The key is checked as well:
            // an index alone could name a neighbour for the frame a fold re-lays the grid.
            val cell = cells.getOrNull(item.index - 1) as? GalleryCell.Tile ?: return null
            return cell.takeIf { it.key == item.key }
        }

        fun slideTo(at: Offset) {
            val start = slide.start ?: return
            val tile = tileAt(at) ?: return
            if (tile.node.path == slide.last) return
            slide.last = tile.node.path
            pick.pane.dragSelectTo(slide.before, start, tile.node.path, slide.adding)
        }

        val hands = GalleryHands(
            tileAt = ::tileAt,
            tap = { t ->
                pick.tap(t.node) { vm.openHomeEntry(t.node, cells.mapNotNull { (it as? GalleryCell.Tile)?.node }) }
            },
            hold = { t ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                slide.start = t.node.path
                slide.last = null
                // Decided once, at the press, by what it found: see `longPressAction`. Against
                // `selectionNow`, so during a pick the pick's list is what it found.
                when (pick.pane.holdAction(t.node.path)) {
                    dev.niccc2007.filet.browser.LongPressAction.MENU -> {
                        slide.adding = true
                        slide.peeking = true
                        onPeek(t.node)
                    }
                    dev.niccc2007.filet.browser.LongPressAction.MENU_FOR_SELECTION -> {
                        // A slide from a selected tile takes the run back out.
                        slide.adding = false
                        slide.peeking = true
                        onPeek(t.node)
                    }
                    dev.niccc2007.filet.browser.LongPressAction.EXTEND -> {
                        slide.adding = true
                        pick.hold(t.node) {}
                    }
                }
                slide.before = pick.pane.selectionNow()
            },
            slideStart = { at ->
                if (slide.peeking) {
                    slide.peeking = false
                    onUnpeek()
                }
                slide.pointer = at
                slideTo(at)
                slide.auto?.cancel()
                slide.auto = scope.launch {
                    while (isActive) {
                        val p = slide.pointer ?: break
                        val step = dev.niccc2007.filet.browser.edgeScrollStep(p.y, heightPx, zonePx, stepPx)
                        if (step != 0f) {
                            state.scrollBy(step)
                            // A tile scrolled under a finger held still joins the run as well.
                            slideTo(p)
                        }
                        withFrameNanos { }
                    }
                }
            },
            slideMove = { at ->
                slide.pointer = at
                slideTo(at)
            },
            slideEnd = {
                slide.pointer = null
                slide.start = null
                slide.auto?.cancel()
                slide.auto = null
            },
        )
        val live = rememberUpdatedState(hands)

        // One item sits above the cells: the header. Stated as the offset so the warm-up's
        // index lands on the tile it names.
        WarmAhead(state = state, count = cells.size, offset = 1) { i ->
            val node = (cells.getOrNull(i) as? GalleryCell.Tile)?.node ?: return@WarmAhead
            if (Thumbnails.canPreview(node.extension)) {
                runCatching {
                    Thumbnails.load(context, vm.vfs, node.path, node.size, node.mtime, px, ahead = true)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = state,
            // Ahead of the grid's own scrolling in the modifier chain, so it is the parent: the
            // grid sees a slide first, and backs off once the hold has claimed the finger.
            modifier = Modifier.fillMaxSize().pointerInput(state) { galleryTouch(live) },
            contentPadding = PaddingValues(horizontal = GalleryCells.EDGE, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(grid.gap),
            verticalArrangement = Arrangement.spacedBy(grid.gap),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed()) { header() } }
            if (cells.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.bleed()) { empty() } }
            items(
                count = cells.size,
                key = { i -> cells[i].key },
                span = { i -> if (cells[i] is GalleryCell.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { i -> if (cells[i] is GalleryCell.Day) 0 else 1 },
            ) { i ->
                when (val c = cells[i]) {
                    is GalleryCell.Day -> Box(Modifier.bleed()) {
                        DayHeader(c.group, c.group.key in collapsed) { onFold(c.group) }
                    }
                    is GalleryCell.Tile -> GalleryTile(
                        node = c.node,
                        name = c.entry.name,
                        side = grid.tile,
                        captioned = captioned,
                        video = category == MediaCategory.VIDEOS,
                        selecting = selecting,
                        selected = pick.has(c.node.path),
                        // Touch is the grid's; these are what a screen reader offers instead.
                        onClick = { live.value.tap(c) },
                        onLongClick = { live.value.hold(c) },
                    )
                }
            }
            item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/** How close to the top or bottom a slide has to come before the grid scrolls under it. */
private val SLIDE_EDGE_ZONE = 72.dp

/** The fastest the grid scrolls under a slide, per frame, with the finger right at the edge. */
private val SLIDE_EDGE_STEP = 22.dp

/** One hold-and-slide's working state. Plain fields: written and read on the main thread only. */
private class Slide {
    var start: VPath? = null
    var before: Set<VPath> = emptySet()
    var adding = true
    var last: VPath? = null
    var pointer: Offset? = null
    var peeking = false
    var auto: Job? = null
}

/** What the grid's one touch handler calls into, rebuilt each composition and read live. */
private class GalleryHands(
    val tileAt: (Offset) -> GalleryCell.Tile?,
    val tap: (GalleryCell.Tile) -> Unit,
    val hold: (GalleryCell.Tile) -> Unit,
    val slideStart: (Offset) -> Unit,
    val slideMove: (Offset) -> Unit,
    val slideEnd: () -> Unit,
)

private const val EARLY_TAP = 1
private const val EARLY_GONE = 2

/**
 * Tap, hold, and hold-then-slide over the gallery, as one gesture.
 *
 * Written out rather than built from `detectTapGestures` and `detectDragGesturesAfterLongPress`,
 * because those are two detectors racing for one finger: whichever saw the long press first
 * consumed it, and the other was cancelled or fired as well. Here the finger is one gesture with
 * three outcomes, decided in order:
 *
 * 1. Lifted before the hold time, without travelling: a tap.
 * 2. Travelled, or claimed by the grid's own scroll, first: not ours. Nothing is consumed before
 *    this point, which is what leaves the grid free to scroll and the pager free to swipe.
 * 3. Held still to the timeout: a hold, and every move after it is consumed - the grid would
 *    otherwise scroll under the finger while it selects. Past twice the touch slop it is a slide.
 */
private suspend fun PointerInputScope.galleryTouch(live: State<GalleryHands>) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tile = live.value.tileAt(down.position) ?: return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = 0
            while (result == 0) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                result = when {
                    c == null -> EARLY_GONE
                    c.changedToUp() -> if (c.isConsumed) EARLY_GONE else {
                        c.consume()
                        EARLY_TAP
                    }
                    c.isConsumed || (c.position - down.position).getDistance() > slop -> EARLY_GONE
                    else -> 0
                }
            }
            result
        }
        when (early) {
            EARLY_TAP -> {
                live.value.tap(tile)
                return@awaitEachGesture
            }
            EARLY_GONE -> return@awaitEachGesture
        }
        live.value.hold(tile)
        var sliding = false
        try {
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (c.changedToUp()) {
                    c.consume()
                    break
                }
                c.consume()
                if (!sliding && (c.position - down.position).getDistance() > slop * 2f) {
                    sliding = true
                    live.value.slideStart(c.position)
                } else if (sliding) {
                    live.value.slideMove(c.position)
                }
            }
        } finally {
            // Also on a cancelled gesture - the screen going, the pointer stolen - so the edge
            // scroll never outlives the finger that started it.
            if (sliding) live.value.slideEnd()
        }
    }
}

/**
 * A full-width row out over the grid's side padding, so the header and the day headers line up
 * with the list layout's instead of sitting [GalleryCells.EDGE] in from both edges.
 */
private fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
    val edge = GalleryCells.EDGE.roundToPx()
    val wide = constraints.maxWidth + edge * 2
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-edge, 0) }
}

@Composable
private fun GalleryTile(
    node: VNode,
    name: String,
    side: Dp,
    captioned: Boolean,
    video: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Filet.colors
    // A tile is a full-bleed picture, so the tinted ground a list row uses for a selected row or
    // for "the pick has taken this one" is not visible on one. A ring is, and it says the same.
    val chosen = dev.niccc2007.filet.browser.isChosenForPick(node.path)
    val ringed = chosen || selected
    Column(
        Modifier
            .width(side)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (ringed) Modifier.border(2.dp, colors.accent, RoundedCornerShape(6.dp))
                else Modifier,
            )
            .semantics(mergeDescendants = true) {
                this.selected = selected
                onClick(label = if (selecting) "Select" else "Open") { onClick(); true }
                onLongClick(label = "Options") { onLongClick(); true }
            },
    ) {
        Box(
            Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            // The component every list and tile uses, so a picture decoded here is already
            // cached for the list, the file browser and the Home card.
            FileThumb(node = node, size = side, fallbackTint = colors.fg3, glyphSize = side * 0.28f)
            if (selected) Box(Modifier.matchParentSize().background(colors.accent.copy(alpha = 0.18f)))
            // A still frame does not say "video" on its own, and a tap starts playback rather
            // than opening a picture - so the tile says which it is.
            if (video) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FiletIcons.PlaySolid, "Video", tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
            // Only while selecting, as in the folder grid: on every tile at rest it read as a
            // pick already under way. The whole tile is the target, so this is a mark, not a button.
            if (selecting) {
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    if (selected) {
                        dev.niccc2007.filet.browser.SelectMark(true, side = 20.dp)
                    } else {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.18f))
                                .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        )
                    }
                }
            }
        }
        if (captioned) {
            val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
            val ink = Filet.colors.accent
            dev.niccc2007.filet.browser.ListNameText(
                text = remember(name, query, ink) { dev.niccc2007.filet.ui.highlighted(name, query, ink) },
                color = MaterialTheme.colorScheme.onSurface,
                startSp = 11f,
                lines = 2,
                lineHeightFor = { it + 2f },
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 6.dp),
            )
        }
    }
}

/** One item of the gallery: a day's header across the whole row, or one file's tile. */
internal sealed interface GalleryCell {
    val key: String

    class Day(val group: DayGroup) : GalleryCell {
        override val key = "h:" + group.key
    }

    class Tile(val dayKey: String, val entry: HistoryEntry) : GalleryCell {
        override val key = "e:" + dayKey + ":" + entry.path
        // Built when the tile is first drawn, not when the list is: a parse per file for three
        // thousand photos is main-thread work for tiles nobody has scrolled to.
        val node by lazy(LazyThreadSafetyMode.NONE) { VNode(VPath.parse(entry.path), false, entry.bytes, entry.modified) }
    }
}

/** The gallery's layout decisions, pure, so the arithmetic is a test rather than a hope. */
internal object GalleryCells {
    /** Space at the left and right of the grid. */
    val EDGE = 8.dp

    /** The tile side and column count for a width. */
    data class Grid(val columns: Int, val tile: Dp, val gap: Dp)

    /**
     * Pictures small enough for three across a phone held upright; covers with a caption
     * larger, two across, because a title under a 100dp square wraps into nothing.
     */
    fun grid(width: Dp, captioned: Boolean): Grid {
        val min = if (captioned) 140.dp else 100.dp
        val gap = if (captioned) 10.dp else 3.dp
        val usable = (width - EDGE * 2).coerceAtLeast(min)
        val columns = ((usable + gap) / (min + gap)).toInt().coerceAtLeast(2)
        val tile = (usable - gap * (columns - 1)) / columns
        return Grid(columns, tile, gap)
    }

    /** What [FileThumb] decodes for a tile of [side] - the same sum, so the warm-up matches it. */
    fun decodePx(side: Dp): Int = (side.value * 2.5f).toInt().coerceIn(48, 320)

    /** Headers and tiles in reading order, leaving out the tiles of a folded day. */
    fun of(groups: List<DayGroup>, collapsed: Set<String>): List<GalleryCell> {
        val out = ArrayList<GalleryCell>(groups.size + groups.sumOf { it.entries.size })
        for (g in groups) {
            out += GalleryCell.Day(g)
            if (g.key in collapsed) continue
            for (e in g.entries) out += GalleryCell.Tile(g.key, e)
        }
        return out
    }
}
