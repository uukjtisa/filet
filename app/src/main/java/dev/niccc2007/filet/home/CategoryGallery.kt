package dev.niccc2007.filet.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    onLongPress: (VNode) -> Unit,
) {
    val context = LocalContext.current
    // A cover with its title under it for audio, since two covers from one album are the same
    // picture; a bare picture for photos and videos, where the picture IS the file.
    val captioned = category == MediaCategory.AUDIO
    val cells = remember(groups, collapsed) { GalleryCells.of(groups, collapsed) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = GalleryCells.grid(maxWidth, captioned)
        val px = GalleryCells.decodePx(grid.tile)
        val state = dev.niccc2007.filet.browser.rememberKeptGridState("category:" + category.id)

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
            modifier = Modifier.fillMaxSize(),
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
                        // The tiles as laid out, so the viewer swipes to the one beside this.
                        onClick = { vm.openHomeEntry(c.node, cells.mapNotNull { (it as? GalleryCell.Tile)?.node }) },
                        onLongClick = { onLongPress(c.node) },
                    )
                }
            }
            item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(28.dp)) }
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
@OptIn(ExperimentalFoundationApi::class)
private fun GalleryTile(
    node: VNode,
    name: String,
    side: Dp,
    captioned: Boolean,
    video: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Filet.colors
    // A tile is a full-bleed picture, so the tinted ground a list row uses for "the pick has
    // taken this one" is not visible on one. A ring is, and it says the same thing.
    val chosen = dev.niccc2007.filet.browser.isChosenForPick(node.path)
    Column(
        Modifier
            .width(side)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (chosen) Modifier.border(2.dp, colors.accent, RoundedCornerShape(6.dp))
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier.size(side).clip(RoundedCornerShape(6.dp)).background(colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            // The component every list and tile uses, so a picture decoded here is already
            // cached for the list, the file browser and the Home card.
            FileThumb(node = node, size = side, fallbackTint = colors.fg3, glyphSize = side * 0.28f)
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
