package dev.niccc2007.filet.browser

import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.ui.LocalRowPulse
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.RowPulse
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a pane of a given width is allowed to render.
 *
 * Each pane is its own container (mock round 3): a 180dp pane lays out like a 180dp screen,
 * not like the phone it is on. Below 270dp it *overrides the view step* entirely, because
 * six steps need room a narrow pane does not have.
 */
/**
 * @param showDate whether there is ROOM for a date in the side column. Whether one is WANTED is
 *   [EntryDisplay.sideFile] or [EntryDisplay.sideFolder], and both have to agree - width is a
 *   constraint, the setting is a choice, and collapsing them into one flag is how a preference
 *   became unreachable on a phone. [SideColumn.fits] is where they meet.
 */
/**
 * @param oneLineName whether a name that does not fit should be cut rather than wrapped. The
 *   two-line steps allow a second line for the rare long name, which is right at full width
 *   and wrong in a split: at half a phone almost EVERY name needed the second line, so a
 *   folder list came out as ".archivete / mp", ".DataStor / age" - a ragged column of broken
 *   words. One line and an ellipsis says the same thing and stays a list.
 */
data class PaneMetrics(
    val step: ViewStep,
    val showDate: Boolean,
    val showSize: Boolean,
    val showChips: Boolean,
    val oneLineName: Boolean = false,
    /**
     * How many lines a name may wrap onto, from `Prefs.nameLines`. Issue #11.
     *
     * Capped by the row's own shape rather than taken as given: a one-line step has no room to
     * wrap into and would overlap the row below it. See [nameLineCount].
     */
    val nameLines: Int = 2,
    /** Carried so the things that size themselves against the pane can ask. See [TreeScale]. */
    val widthDp: Int = 0,
) {
    companion object {
        fun forWidth(widthDp: Int, requested: ViewStep, nameLines: Int = 2) = PaneMetrics(
            widthDp = widthDp,
            step = if (widthDp < 270 && requested.isGrid) ViewStep.LIST
            else if (widthDp < 200) ViewStep.COMPACT_LIST
            else requested,
            showDate = widthDp >= 330,
            showSize = widthDp >= 180,
            showChips = widthDp >= 180,
            oneLineName = widthDp < 300,
            nameLines = nameLines,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier = Modifier,
    /** Off in the two compact list steps, where the glyph is only a few pixels wide. */
    thumbnails: Boolean = true,
    /** What this row shows. See [EntryDisplay] for why the date column defaults off. */
    display: EntryDisplay = EntryDisplay(),
    /** Entries inside, for a folder. Null until read, and the column then shows nothing. */
    count: Int? = null,
    /** How deep in the tree view this row sits. 0 everywhere else. */
    depth: Int = 0,
    /** Which ancestor spines continue past this row. See `TreeRow`. */
    spines: List<Boolean> = emptyList(),
    /** Whether this row is the last of its siblings, so its own spine stops here. */
    lastOfSiblings: Boolean = false,
    /** True for any row in a tree, so rows with no chevron still line up with those that have one. */
    treeRow: Boolean = false,
    /**
     * Which part of the working folder's outline this row draws, if any. See [ActiveEdge].
     */
    edge: ActiveEdge? = null,
    /**
     * How far in this tree steps per level, and where level zero starts. See [TreeScale].
     *
     * It reaches both the padding below AND the guide lines, from this one value, which is
     * what makes "the lines are still connected" true by construction rather than by care.
     */
    steps: TreeScale.Steps = FLAT,
    /** Null for a row that cannot open: a file, or the tree view being off. */
    openInPlace: Boolean? = null,
    onOpenInPlace: (() -> Unit)? = null,
    /** Whether a selection is live in this pane. Taps are timed differently - see [RowTaps]. */
    selecting: Boolean = false,
    /**
     * Tapping the icon itself toggles the row's selection, ZArchiver's way - null where the row
     * cannot be selected. A target of its own, so the first selection is a tap, not a press.
     */
    onIconClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Where the second tap landed, in root coordinates, for the menu it opens. */
    onDoubleClick: (Offset) -> Unit = {},
    /**
     * Taken in by a selected folder above it, so acted on with that folder rather than on its
     * own - see `TreeSelection`. Drawn as a fainter selection: it IS going to be copied or
     * deleted, and a row that looked unselected would hide that.
     */
    included: Boolean = false,
) {
    // The rows' own long-press timing - see RowTaps. Provided here so the drag detector that
    // arrives in `modifier` reads it too, which is the press that starts every selection.
    val base = LocalViewConfiguration.current
    val quick = remember(base) { RowTaps.QuickPress(base) }
    CompositionLocalProvider(LocalViewConfiguration provides quick) {
        FileRowBody(
            node, metrics, selected, dropTarget, modifier, thumbnails, display, count, depth, spines,
            lastOfSiblings, treeRow, edge, steps, openInPlace, onOpenInPlace, selecting, onIconClick,
            onClick, onLongClick, onDoubleClick, included,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRowBody(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier,
    thumbnails: Boolean,
    display: EntryDisplay,
    count: Int?,
    depth: Int,
    spines: List<Boolean>,
    lastOfSiblings: Boolean,
    treeRow: Boolean,
    edge: ActiveEdge?,
    steps: TreeScale.Steps,
    openInPlace: Boolean?,
    onOpenInPlace: (() -> Unit)?,
    selecting: Boolean,
    onIconClick: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: (Offset) -> Unit,
    included: Boolean,
) {
    val colors = Filet.colors
    val accent = colors.accent
    val kind = FileKind.of(node)
    // Held as the State object, NOT delegated with `by`, and that is the whole point.
    //
    // `by` unwraps an animation in the COMPOSITION scope, so the row that reads it recomposes on
    // every frame of the fade. Read instead inside `drawBehind` below, the same fade invalidates
    // only the draw phase - one rectangle - and the row's composition is left alone. With a
    // screenful of rows each running its own colour animation, that is the difference between a
    // fling that recomposes the list continuously and one that does not.
    val bg = animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            included -> colors.sel.copy(alpha = colors.sel.alpha * INCLUDED_ALPHA)
            else -> Color.Transparent
        },
        // In at once, out with the fade. A highlight that eases in reads as the tap having been
        // slow to register, which is what selecting was reported as.
        animationSpec = if (selected || dropTarget || included) snap() else Motion.fast(),
        label = "rowBackground",
    )
    val step = metrics.step
    // The outline ARRIVES rather than being there.
    //
    // Drawn at full strength on the frame the branch opens, it reads as a box snapping into
    // existence over rows that are themselves still appearing - two unrelated things
    // happening at once, which is what made expanding a folder feel wrong rather than slow.
    // Faded and drawn in from slightly outside, it reads as one movement.
    //
    // The last edge is held through the fade OUT, or a row would lose its piece of the box
    // the instant it stopped being in it and the outline would come apart before it faded.
    var heldEdge by remember { mutableStateOf(edge) }
    if (edge != null) heldEdge = edge
    // Draw-phase read, for the reason given on `bg`.
    val framed = animateFloatAsState(
        if (edge != null) 1f else 0f,
        animationSpec = tween(FRAME_IN_MS, easing = FastOutSlowInEasing),
        label = "workingFrame",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // ORDER MATTERS, and it is not the usual convention.
        //
        // `modifier` here carries the drag detector, and it goes AFTER `combinedClickable`
        // rather than before it. Compose delivers PointerEventPass.Main from the innermost
        // modifier outwards, so whichever pointer modifier is last in the chain sees the
        // gesture first - and with the drag first, `combinedClickable` won every long press,
        // selected the row, and no drag ever started. That is the whole reason dragging a
        // file between panes appeared to do nothing.
        //
        // The drag detector then owns the long press. It selects on onDragStart, so nothing
        // is lost; see the call sites, which pass no onLongClick when they pass a drag.
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = step.rowHeight.dp)
            .rowTaps(selecting, onTap = onClick, onLongClick = onLongClick, onDoubleTap = onDoubleClick)
            .then(modifier)
            .drawBehind {
                // The row's own background, drawn rather than composed - see the note on `bg`.
                // Still first in the block, so it stays underneath the guides and the outline
                // exactly as `.background()` did.
                val fill = bg.value
                if (fill != Color.Transparent) drawRect(fill)
                if (treeRow) treeGuides(depth, spines, lastOfSiblings, openInPlace, steps, accent)
                val lit = framed.value
                heldEdge?.takeIf { lit > 0.01f }?.let { activeRail(it, accent, lit) }
            }
            .padding(
                start = (steps.startDp + depth * steps.indentDp).dp,
                end = 10.dp,
                top = if (step.twoLine) 6.dp else 3.dp,
                bottom = if (step.twoLine) 6.dp else 3.dp,
            ),
    ) {
        // The chevron, and only where it can do something.
        //
        // Its own target, separate from the row: tapping a folder's NAME still navigates
        // into it, which is what keeps the path bar at the top meaning what it has always
        // meant. A tree where the only way in is a chevron is a tree that has quietly
        // replaced the browser instead of sitting inside it.
        if (openInPlace != null && onOpenInPlace != null) {
            // Draw-phase read, for the reason given on `bg`: a chevron turning must not
            // recompose the row it sits in.
            val turn = animateFloatAsState(
                if (openInPlace) 0f else -90f,
                animationSpec = Motion.fast(),
                label = "branch",
            )
            Icon(
                FiletIcons.Down, if (openInPlace) "Close" else "Open here",
                tint = colors.fg3,
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenInPlace)
                    .padding(7.dp)
                    .graphicsLayer { rotationZ = turn.value },
            )
        } else if (treeRow || depth > 0) {
            // The chevron's width is reserved whether or not there is one. Without this a
            // file, or a folder with nothing in it, sat a chevron to the left of everything
            // around it - which reads as the list being broken rather than as that row being
            // different.
            Spacer(Modifier.width(26.dp))
        }
        // The icon is its own target when the row can be selected: a tap on it toggles the
        // selection, and the tick on its corner says so.
        Box(
            if (onIconClick != null) {
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onIconClick)
            } else {
                Modifier
            },
        ) {
            FileThumb(
                node = node,
                size = step.icon.dp,
                fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
                enabled = thumbnails,
            )
            SelectMark(
                (selected || included) && onIconClick != null,
                Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer { if (included && !selected) alpha = INCLUDED_ALPHA },
                side = (step.icon * 0.5f).coerceIn(12f, 18f).dp,
            )
        }
        Spacer(Modifier.width(10.dp))
        // ── everything inside this folder, if anything already knows it ───────────────────
        //
        // Hoisted above both surfaces that can ask for it. The line under the name and the
        // column beside it are configured separately and either can carry `Folder total`, so
        // the lookup belongs to the ROW rather than to one of them - two lookups for one figure
        // would double the work and let the two surfaces disagree while they settle.
        //
        // Read, never measured; see `FolderBytes`. The suspend read is cheap after the first
        // one per crawl generation, which is what `FolderMeasure.indexTally` is for.
        val bytes = LocalFolderBytes.current
        val side = SideColumn.slotFor(display, node.isDir)
        val sideFits = SideColumn.fits(side, metrics)
        val wantsTotal = node.isDir && bytes != null && (
            (step.twoLine && DetailPart.TOTAL in display.parts) ||
                (side == DetailPart.TOTAL && sideFits)
            )
        // Polled until it stops moving, not read once.
        //
        // A one-shot read was enough while the only outcome was a figure or nothing. It is not
        // enough now that the row says WHICH of three things is true: a total that arrives as
        // Confirming would keep its spinner turning for as long as the row stayed on screen,
        // because nothing would ever ask again - and a spinner that never stops is a worse
        // claim than no spinner, since it reads as the app being stuck rather than as a figure
        // settling.
        //
        // Stops on its own three ways: the moment the answer is Settled, after [SETTLE_TRIES]
        // attempts so a device with indexing switched off is not polled for ever, and when the
        // row leaves the list, which cancels the coroutine. Every attempt after the first is a
        // map lookup, so the cost of watching is a timer rather than work.
        val total by produceState<FolderTotal>(FolderTotal.Unknown, node.path, wantsTotal, bytes) {
            if (!wantsTotal) {
                value = FolderTotal.Unknown
                return@produceState
            }
            var tries = 0
            while (tries < SETTLE_TRIES) {
                val next = runCatching { bytes.of(node.path) }.getOrDefault(FolderTotal.Unknown)
                value = next
                if (next is FolderTotal.Settled) return@produceState
                // Ask for the real thing. The index can only ever offer a floor, so without
                // this the row would show "703 MB+" for ever and never the 39 GB that is
                // actually there. The request is de-duplicated and queued one at a time, so
                // asking again on every tick costs a set lookup.
                if (next.wantsWalk()) bytes.measure(node.path)
                tries++
                delay(SETTLE_POLL_MS)
            }
        }
        Column(Modifier.weight(1f)) {
            // Whole, never dotted: it gives up size down to 11sp, then fades before its
            // extension. See ListName.
            ListNameText(
                name = node.name,
                color = MaterialTheme.colorScheme.onSurface,
                startSp = step.nameSize.toFloat(),
                lines = nameLineCount(step.twoLine, metrics.oneLineName, metrics.nameLines),
                isDir = node.isDir,
                fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal,
            )
            if (step.twoLine && display.parts.isNotEmpty()) {
                // Remembered, not recomputed. Formatting a date is cheap once and expensive four
                // hundred times a second: this is a list row, so the body below runs for every
                // visible row on every recomposition, and a fling recomposes constantly. The
                // keys are everything the line is derived from, so a row whose file has not
                // changed formats its line exactly once.
                val pieces = remember(node.isDir, node.size, node.mtime, display, count, node.name, total) {
                    RowDetails.pieces(
                        parts = display.parts,
                        isDir = node.isDir,
                        size = node.size,
                        at = node.mtime,
                        count = count,
                        name = node.name,
                        display = display,
                        total = total,
                    )
                }
                DetailLine(pieces = pieces, total = total, size = step.metaSize, colour = colors.fg3)
            }
        }
        // The column beside the name: one thing, or nothing. See `SideColumn` for why it is one
        // slot rather than the two independent booleans it replaced, and why a file and a folder
        // choose separately.
        if (side != null && sideFits) {
            val text = remember(side, node.isDir, node.size, node.mtime, count, node.name, display, total) {
                RowDetails.one(
                    part = side,
                    isDir = node.isDir,
                    size = node.size,
                    at = node.mtime,
                    count = count,
                    name = node.name,
                    display = display,
                    total = total,
                )
            }
            Spacer(Modifier.width(8.dp))
            SideCell(
                text = text,
                pending = side == DetailPart.TOTAL,
                total = total,
                widthDp = SideColumn.widthDp(side, node.isDir),
                size = step.metaSize,
                colour = colors.fg3,
            )
        }
    }
}

/**
 * The line under a name, which stays ONE Text whenever it can.
 *
 * ## Two paths, and why the cheap one has to stay cheap
 *
 * A settled row draws one Text holding the whole joined line, exactly as it always did. That is
 * the overwhelming majority of rows - every file, and every folder whose total is known and not
 * being re-derived - so the common case pays nothing for a feature it is not using. Splitting
 * every line into a Text per part would put three or four text nodes on every row in the list to
 * serve the few that are pending, which is the sort of trade a fling notices.
 *
 * The pending path splits, because it has to. The alternative is applying the pulse to the whole
 * line, and that would say "this may change" about the parts beside the total - a settled date is
 * not provisional, and dimming it to describe the uncertainty of the figure next to it is a claim
 * about the wrong field. A line that misreports which of its own parts is firm is worse than one
 * that costs an extra text node while it settles.
 *
 * ## Where the clock is read
 *
 * Inside `graphicsLayer`, which runs after composition, so the breath invalidates the draw phase
 * alone. Read with `by` in this function's body instead and every pending row in the list
 * recomposes on every frame of the animation - see `RowPulse` for the whole rule, and the row
 * background in `FileRow` for the same decision taken for the same reason.
 */
@Composable
private fun DetailLine(
    pieces: List<RowDetails.Piece>,
    total: FolderTotal,
    size: Int,
    colour: Color,
) {
    if (pieces.isEmpty()) return
    val pulse = LocalRowPulse.current
    val hasTotal = pieces.any { it.part == DetailPart.TOTAL }
    // Pending means: this line carries a folder total, and that total is either missing or on
    // its way to being replaced. Anything else draws the one-Text path.
    val pending = hasTotal && (total.busy() || !total.known())
    if (!pending) {
        // Nothing to say is nothing drawn. A configuration can be legitimate and still produce
        // an empty line for a given entry - "kind" on a folder, "file size" on a folder - and an
        // empty Text still reserves a line's height, which would leave a ragged list where some
        // rows are taller than others for no visible reason.
        Text(
            text = pieces.joinToString(RowDetails.JOIN) { it.text },
            fontSize = size.sp,
            color = colour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val known = total.known()
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((i, piece) in pieces.withIndex()) {
            if (i > 0) {
                Text(RowDetails.JOIN, fontSize = size.sp, color = colour, maxLines = 1)
            }
            val isTotal = piece.part == DetailPart.TOTAL
            Text(
                text = piece.text,
                fontSize = size.sp,
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (!isTotal) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        alpha = if (known) {
                            RowPulse.figure(pulse.phase.value)
                        } else {
                            RowPulse.dash(pulse.phase.value)
                        }
                    }
                },
            )
        }
        // Only when a figure is actually being re-derived. The missing-figure state gets the
        // breathing dash and NO spinner: there is nothing in flight for this row to be busy
        // about, and an arc turning over a row that holds no number promises an answer that may
        // never come.
        if (total.busy()) {
            Spacer(Modifier.width(5.dp))
            ConfirmSpinner(size = (size + 1).dp, colour = colour)
        }
    }
}

/**
 * One fixed-width cell on the right of a row.
 *
 * Fixed width, and right-aligned inside it, because the entire value of this column is that it
 * lines up down the list - digits that do not share a right edge cannot be compared at a glance,
 * which is why the size lives here as well as being offered on the line.
 *
 * **The spinner goes on the LEFT of the figure here**, which is the opposite of the line under
 * the name, and the reason is that right edge. A spinner appended after the number would push
 * the number left by its own width for exactly as long as the figure was unsettled, so a list of
 * folders mid-crawl would have a ragged column that straightened itself out one row at a time.
 * On the line under the name there is no edge to hold, so the arc trails the text, where it
 * reads more naturally.
 */
@Composable
private fun SideCell(
    text: String,
    pending: Boolean,
    total: FolderTotal,
    widthDp: Int,
    size: Int,
    colour: Color,
) {
    val pulse = LocalRowPulse.current
    if (text.isEmpty()) {
        // Still takes the width. A folder with no count yet must not let the rows around it
        // shift sideways when its figure lands.
        Spacer(Modifier.width(widthDp.dp))
        return
    }
    val breathing = pending && (total.busy() || !total.known())
    Row(
        modifier = Modifier.width(widthDp.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending && total.busy()) {
            ConfirmSpinner(size = (size + 1).dp, colour = colour)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            fontSize = size.sp,
            // The list face with tabular figures, not Cascadia Mono. A code font in a list of
            // documents was the fault; what the column actually needs is digits that line
            // up, which is a font FEATURE rather than a whole family.
            fontFamily = null,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            color = colour,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (!breathing) {
                        Modifier
                    } else {
                        Modifier.graphicsLayer {
                            alpha = if (total.known()) {
                                RowPulse.figure(pulse.phase.value)
                            } else {
                                RowPulse.dash(pulse.phase.value)
                            }
                        }
                    },
                ),
        )
    }
}

/**
 * The small turning arc that means "this figure is being re-derived".
 *
 * A `Canvas` rather than a `CircularProgressIndicator`: the Material one is 40dp at its smallest
 * without surgery, animates on its own clock - which is the per-row animation `RowPulse` exists
 * to avoid - and carries a determinate/indeterminate API this has no use for. An arc is four
 * numbers and reads at 12dp, which is the size that fits beside a row's metadata.
 *
 * The phase is read inside the draw lambda, so the turn costs a redraw of one small square and
 * never a recomposition. Under reduce-motion the clock is parked and this is a static arc, which
 * still says "in progress" without moving - see `RowPulse.STILL`.
 */
@Composable
private fun ConfirmSpinner(size: Dp, colour: Color) {
    val pulse = LocalRowPulse.current
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        drawArc(
            color = colour,
            startAngle = RowPulse.sweep(pulse.phase.value),
            // A quarter of the circle left open. A full ring cannot be seen to turn.
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(this.size.width - stroke, this.size.height - stroke),
        )
    }
}

/** How long a row waits between asking whether a pending total has settled. */
private const val SETTLE_POLL_MS = 1_200L

/**
 * How many times it asks before giving up.
 *
 * Bounded because a row whose total is never going to arrive - indexing switched off, a remote
 * folder nothing has walked - would otherwise hold a timer for as long as it was on screen.
 * Twenty-five attempts is half a minute of watching, which covers a crawl passing the folder,
 * and scrolling the row away and back starts a fresh watch.
 */
private const val SETTLE_TRIES = 25


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier = Modifier,
    /** Off in the two compact list steps, where the glyph is only a few pixels wide. */
    thumbnails: Boolean = true,
    /** Whether a selection is live. See [RowTaps]. */
    selecting: Boolean = false,
    /** The corner circle's tap, offered only while [selecting]. Null hides the circle. */
    onIconClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: (Offset) -> Unit = {},
) {
    val base = LocalViewConfiguration.current
    val quick = remember(base) { RowTaps.QuickPress(base) }
    CompositionLocalProvider(LocalViewConfiguration provides quick) {
        Box {
            FileTileBody(node, metrics, selected, dropTarget, modifier, thumbnails, selecting, onClick, onLongClick, onDoubleClick)
            // Only while selecting, as in Photos and Files. On every tile at rest it read as a
            // pick already in progress.
            if (onIconClick != null && selecting) {
                CornerSelect(selected, onIconClick, Modifier.align(Alignment.TopStart))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTileBody(
    node: VNode,
    metrics: PaneMetrics,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier,
    thumbnails: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: (Offset) -> Unit,
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    val step = metrics.step
    // Draw-phase read, for the reason given in FileRow: a selection fade must cost a redraw
    // rather than a recomposition of every tile on screen.
    val bg = animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            else -> Color.Transparent
        },
        // In at once, out with the fade - see FileRow.
        animationSpec = if (selected || dropTarget) snap() else Motion.fast(),
        label = "tileBackground",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        // The drag detector goes last - see the note in FileRow. Same trap, same fix.
        // The whole cell, not the name's width: a short name otherwise sat its icon at the
        // cell's left edge while a long one centred it, so icons landed anywhere along a row.
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                val fill = bg.value
                if (fill != Color.Transparent) drawRect(fill)
            }
            .rowTaps(selecting, onTap = onClick, onLongClick = onLongClick, onDoubleTap = onDoubleClick)
            .then(modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        val picture = remember(metrics.widthDp, step) { TileGrid.picture(metrics.widthDp - 12f, step) }
        // A fixed slot round the thumbnail, because a glyph is drawn smaller than a picture
        // and a tile without one would otherwise be shorter than its neighbours.
        Box(Modifier.size(picture.slot.dp), contentAlignment = Alignment.Center) {
            FileThumb(
                node = node,
                size = picture.slot.dp,
                glyphSize = picture.glyph.dp,
                fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
                enabled = thumbnails,
            )
        }
        Spacer(Modifier.height(7.dp))
        // Whole, at whatever size it fits, in an area the same height on every tile - see
        // TileName. Five or more across is a wide screen, which keeps a higher floor.
        TileNameText(
            name = node.name,
            isDir = node.isDir,
            startSp = TileName.startFor(step),
            color = MaterialTheme.colorScheme.onSurface,
            wide = step.tile != null &&
                TileGrid.columns(metrics.widthDp - 12f, step.tile.toFloat()) >= 5,
            modifier = Modifier.fillMaxWidth(),
        )
        // Folders deliberately print nothing here: a bare em dash where a size would be
        // reads as a failed lookup rather than as "not applicable".
        if (!node.isDir && metrics.showSize) {
            Text(
                remember(node.size) { humanSize(node.size) },
                fontSize = step.metaSize.sp,
                color = colors.fg3,
            )
        }
    }
}

/** A hit inside a search result list: name plus where it was found. */
@Composable
fun SearchResultRow(
    node: VNode,
    where: String,
    /**
     * Whether this row's own check has come back. SEARCH.md §14.3.
     *
     * Two states and no more. A row whose check fails is dropped from the list rather than
     * labelled - see HitState, where the rejected third state is written down.
     */
    state: dev.niccc2007.filet.index.HitState = dev.niccc2007.filet.index.HitState.AVAILABLE,
    /** The characters of the name that matched the query, lit so why a row is here is visible. */
    spans: List<IntRange> = emptyList(),
    metrics: PaneMetrics,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.sel else Color.Transparent)
            .combinedClickableCompat(onClick, onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        FileThumb(
            node = node,
            size = metrics.step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // The matched letters lit. `Microsoft.MinecraftUWP_8wekyb3d8bbwe` reads as an
            // unrelated answer to "minecraft" until the piece that matched is visible.
            val ink = colors.accent
            val label = remember(node.name, spans, ink) { matchedName(node.name, spans, ink) }
            ListNameText(label, MaterialTheme.colorScheme.onSurface, metrics.step.nameSize.toFloat(), lines = 1, isDir = node.isDir)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only while this row's own check is outstanding. Once it passes there is
                // nothing left to say, and a badge on every row saying so is noise. It used to
                // follow whether a crawl was running, which is how checked rows came to read
                // Confirming for minutes - see showBadge.
                if (dev.niccc2007.filet.index.showBadge(state)) {
                    val tint = colors.warn
                    Text(
                        state.label,
                        fontSize = (metrics.step.metaSize - 0.5f).sp,
                        color = tint,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tint.copy(alpha = 0.13f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    where,
                    fontSize = metrics.step.metaSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.fg3,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (!node.isDir && metrics.showSize) {
            Text(humanSize(node.size), fontSize = metrics.step.metaSize.sp, fontFamily = FontFamily.Monospace, color = colors.fg3)
        }
    }
}

/**
 * A name with the characters a search matched marked the way every list marks them:
 * [dev.niccc2007.filet.ui.matchStyle].
 *
 * Bug identified here: this drew its own style - the letters in the accent - after the shared
 * highlight had moved to a wash behind the letters, so the search results of the file list kept
 * the light-theme fault that the shared one had fixed. Both now read the one style.
 *
 * Spans out of range are clamped rather than trusted: they were computed on the case-folded name,
 * and the rare name that folding changes the length of must not throw from inside a list.
 */
internal fun matchedName(
    name: String,
    spans: List<IntRange>,
    ink: Color,
): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    append(name)
    val style = dev.niccc2007.filet.ui.matchStyle(ink)
    for (r in spans) {
        val start = r.first.coerceIn(0, name.length)
        val end = (r.last + 1).coerceIn(start, name.length)
        if (end > start) addStyle(style, start, end)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun iconTint(kind: FileKind, accent: Color, onSurfaceVariant: Color, fg2: Color): Color =
    if (kind == FileKind.FOLDER) accent else if (kind == FileKind.OTHER) fg2 else onSurfaceVariant

fun humanSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "${v.toInt()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** A neutral empty state. Says which of the two empties this is, because they differ. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Filet.colors.fg3, textAlign = TextAlign.Center)
    }
}

/**
 * How far one level of the tree indents, in dp.
 *
 * Twenty-six: exactly the width of the chevron. One level in, the child's arrow sits
 * directly under the right-hand edge of its parent's, which is what lets the elbow run from
 * one arrow to the next instead of crossing the column at an angle. A narrower indent made
 * the levels overlap their own guides.
 */

/** How strongly a row a selected folder takes in is drawn, against a selected row. */
private const val INCLUDED_ALPHA = 0.45f

/**
 * Which part of the rail beside the focused branch a row is responsible for.
 *
 * A lazy list cannot draw one shape across several of its items - there is no "several of its
 * items" to hang a modifier on, and an overlay computed from item offsets is a frame wrong
 * every time anything scrolls, resizes or is inserted. So the rail is drawn in pieces: every
 * row in the block draws its own length of it, the first rounds the top, the last rounds the
 * bottom, and because the rows are adjacent the pieces meet. It then scrolls, recycles and
 * clips for free, and a block whose top has scrolled off shows a rail running off the top -
 * which is the right picture rather than a bug.
 */
enum class ActiveEdge { TOP, MIDDLE, BOTTOM, ONLY }

/**
 * The rail down the side of the focused branch: the folder new things go into, and everything
 * shown inside it.
 *
 * A rail, not the box it replaces. The box went round the whole block, so a long branch was a
 * frame bigger than the screen whose only visible parts were two hairlines at the far edges,
 * and the folder's own row looked no different from its contents. One accent bar down the
 * leading edge says the same thing - this run of rows is where you are - in the column the eye
 * already scans, and it never crosses the names or the guide lines.
 *
 * Two passes, a wide faint one under the bar, so it carries a glow instead of being a stripe
 * stuck on the list. It slides in from the edge as it arrives.
 */
private fun DrawScope.activeRail(edge: ActiveEdge, accent: Color, at: Float) {
    val capTop = edge == ActiveEdge.TOP || edge == ActiveEdge.ONLY
    val capBottom = edge == ActiveEdge.BOTTOM || edge == ActiveEdge.ONLY
    val inset = 4.dp.toPx()
    val top = if (capTop) inset else 0f
    val bottom = if (capBottom) size.height - inset else size.height
    val x = RAIL_X.dp.toPx() - 4.dp.toPx() * (1f - at)
    for ((width, alpha) in RAIL_PASSES) {
        val w = width.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(w / 2f)
        val none = androidx.compose.ui.geometry.CornerRadius.Zero
        // Rounded only at the ends of the block. A butt join where the rows meet is what lets
        // the pieces read as one bar; a rounded end on every row would bead it.
        val shape = androidx.compose.ui.geometry.RoundRect(
            left = x - w / 2f,
            top = top,
            right = x + w / 2f,
            bottom = bottom,
            topLeftCornerRadius = if (capTop) r else none,
            topRightCornerRadius = if (capTop) r else none,
            bottomRightCornerRadius = if (capBottom) r else none,
            bottomLeftCornerRadius = if (capBottom) r else none,
        )
        drawPath(androidx.compose.ui.graphics.Path().apply { addRoundRect(shape) }, accent.copy(alpha = alpha * at))
    }
}

/** Where the rail's centre sits, in dp from the row's leading edge: inside the tree's start. */
private const val RAIL_X = 4.5f

/** Bar widths and alphas, outside in: the glow, then the bar. */
private val RAIL_PASSES = listOf(7f to 0.12f, 3f to 0.9f)

/**
 * How long the rail takes to arrive.
 *
 * Short. It is confirming something that has already happened - the branch is open, the rows
 * are there - so anything long enough to watch is an animation for its own sake.
 */
private const val FRAME_IN_MS = 150

/** Half the chevron's 26dp box, which is where its spine hangs from. */
private const val CHEVRON_HALF = 13

/** A list that is not a tree: the full step, and nothing drawn. */
private val FLAT = TreeScale.Steps(TreeScale.START, TreeScale.INDENT)

/**
 * How far into its 26dp box the arrow glyph actually starts.
 *
 * The chevron is a 26dp target with 7dp of padding, so the drawn arrow is the middle 12dp of
 * it. A guide that stops at the box stops seven dp short of anything visible, which is the
 * gap that made the elbow look like it pointed at nothing - the line was correct and the
 * drawing was not.
 */
private const val ARROW_INSET = 7

/**
 * The lines that say which folder a row is inside.
 *
 * ## Why indentation alone was not enough
 *
 * Bug identified here: four rows at the same indent could be children of four
 * different folders, and nothing on screen says which parent each one belongs to once its
 * header has scrolled away. A line running down from a folder to its own children answers
 * that without reading anything, which is what every tree in every file manager does.
 *
 * ## The colours are assigned by DEPTH, not by folder
 *
 * One hue per level, stepped by [HUE_STEP] round the wheel from the theme's own accent. Per
 * folder would be better in principle and worse in practice - it needs a stable mapping from
 * path to colour that survives scrolling, and two sibling folders would get colours as
 * different as a parent and its child, which is the one distinction these lines exist to
 * make. Per level, every line at the same indent matches, and the nesting reads as layers.
 *
 * 47 degrees is deliberate: it does not divide into 360, so six levels down the colours have
 * not come back round to where they started.
 */
private fun DrawScope.treeGuides(
    depth: Int,
    spines: List<Boolean>,
    lastOfSiblings: Boolean,
    openInPlace: Boolean?,
    steps: TreeScale.Steps,
    accent: Color,
) {
    val indent = steps.indentDp.dp.toPx()
    val start = steps.startDp.dp.toPx()
    val middle = size.height * 0.5f
    val width = 1.2.dp.toPx()
    // A generous corner, but not so generous that it eats the whole run. At 0.46 the arc
    // used twelve of the thirteen dp available and the elbow read as a hook with no line on
    // the end of it; the straight section after the turn is what makes it point somewhere.
    val radius = indent * 0.34f

    // Where the elbow has to ARRIVE.
    //
    // At the thing it is pointing at, which is arrow to arrow, and file to file. A row that
    // has an arrow is joined at the arrow; a row that has none - a file, or a folder with
    // nothing in it - is joined at its icon, running across the blank arrow slot it still
    // reserves. It used to stop at the row's text margin, which is seven dp short of the
    // arrow and twenty short of the icon, so it ended in empty space either way and the
    // tree read as a set of hooks pointing at nothing.
    val arrives = if (openInPlace != null) {
        indent - (CHEVRON_HALF - ARROW_INSET).dp.toPx()
    } else {
        indent + (ICON_SLOT - CHEVRON_HALF).dp.toPx()
    }

    // The stub that joins a parent to its own children.
    //
    // This is what made the lines look detached: an expanded folder drew nothing, so the
    // spine under it began at the TOP of the next row - half a row below the arrow it was
    // supposed to be hanging from, with a gap in between. A parent now draws from its own
    // middle down to its bottom edge, in its children's column, and the two meet.
    if (openInPlace == true) {
        val childX = start + depth * indent + CHEVRON_HALF.dp.toPx()
        drawLine(
            color = levelColour(accent, depth),
            start = Offset(childX, middle),
            end = Offset(childX, size.height),
            strokeWidth = width,
        )
    }
    if (depth == 0) return

    for (level in 0 until depth) {
        // The spine hangs off the PARENT'S ARROW, not from an arbitrary fraction of the
        // indent. That was the whole fault: the line ran down a column seven dp to the left
        // of the chevron it belonged to, so it pointed at nothing at the top and arrived
        // beside the icon at the bottom. A guide that does not start at the thing it is
        // guiding from is worse than no guide.
        val x = start + level * indent + CHEVRON_HALF.dp.toPx()
        val colour = levelColour(accent, level)

        if (level < depth - 1) {
            // An ancestor's spine, passing straight through this row.
            //
            // LEVEL + 1, and that off-by-one is the whole of "the line is still broken".
            //
            // `spines[k]` says whether the ancestor at depth k has siblings after it. The
            // line in column `level` comes down from the ancestor at that depth, and the
            // question it answers is not whether THAT ancestor continues - it is whether
            // that ancestor still has CHILDREN below this row. Which is the same as asking
            // whether the ancestor one level further in, the one this row's branch hangs
            // off, has siblings after it: `spines[level + 1]`.
            //
            // Reading `spines[level]` drew a spine whenever the outer folder had a sibling
            // somewhere further down the screen, even though its own sub-list had finished
            // two rows ago. On screen that is a line starting in mid-air with a gap above
            // it, attached to nothing at either end.
            //
            // Absent means NO line. It used to default to drawing one, which invented a
            // spine for an ancestor that had nothing left below it at all.
            if (spines.getOrElse(level + 1) { false }) {
                drawLine(colour, Offset(x, 0f), Offset(x, size.height), width)
            }
            continue
        }

        // This row's own level: down from the parent, then a rounded corner into the row.
        // The vertical stops at the middle for a last child and carries on for the rest,
        // which is the difference between a tree and a set of unconnected ticks.
        drawLine(
            color = colour,
            start = Offset(x, 0f),
            end = Offset(x, if (lastOfSiblings) middle - radius else size.height),
            strokeWidth = width,
        )
        if (lastOfSiblings) {
            // A quarter circle, so the corner reads as one stroke turning rather than two
            // lines meeting at a notch.
            drawArc(
                color = colour,
                startAngle = 180f,
                sweepAngle = -90f,
                useCenter = false,
                topLeft = Offset(x, middle - radius * 2f),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = width),
            )
        }
        // And it arrives AT the child - see `arrives`.
        drawLine(
            color = colour,
            start = Offset(if (lastOfSiblings) x + radius else x, middle),
            end = Offset(x + arrives, middle),
            strokeWidth = width,
        )
    }
}

/**
 * One level's colour.
 *
 * Soft, and softer the deeper it goes. The first version used one alpha for every level,
 * which put a hard ladder of colour down the left of the screen competing with the file
 * names - these are scaffolding for the eye and should be read without being looked at.
 */
private fun levelColour(accent: Color, level: Int): Color = Color.hsv(
    hue = ((hueOf(accent) + level * HUE_STEP) % 360f + 360f) % 360f,
    saturation = 0.34f,
    value = 0.82f,
).copy(alpha = (GUIDE_ALPHA - level * FADE_PER_LEVEL).coerceAtLeast(0.18f))

/** The accent's own hue, so the ladder starts from the theme rather than from red. */
private fun hueOf(c: Color): Float {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    val d = max - min
    if (d <= 0f) return 0f
    val h = when (max) {
        c.red -> ((c.green - c.blue) / d) % 6f
        c.green -> (c.blue - c.red) / d + 2f
        else -> (c.red - c.green) / d + 4f
    } * 60f
    return if (h < 0f) h + 360f else h
}

/** The icon column, which is where a row with no arrow gets joined instead. */
private const val ICON_SLOT = 26

/** How far round the wheel each level steps. Not a divisor of 360, so levels stay distinct. */
private const val HUE_STEP = 47f

/** Faint. These are scaffolding for the eye, not content. */
private const val GUIDE_ALPHA = 0.42f

/** And fainter with depth, so the outer levels recede instead of stacking up. */
private const val FADE_PER_LEVEL = 0.05f

/**
 * How many lines a name actually gets, which is not simply what the setting says.
 *
 * ## The two caps, and why both are needed
 *
 * **A one-line step stays one line.** Compact's row height is sized for a single line of text;
 * wrapping inside it does not make the row taller, it makes the second line overlap the row
 * below. So the step's own shape is a hard ceiling, and the setting raises the limit for the
 * steps that have room rather than overriding every one of them.
 *
 * **A very narrow pane stays one line too.** In a split pane under 300dp, two lines of a long
 * name is most of the row's height and the list stops being scannable - which is the thing
 * wrapping exists to serve.
 *
 * Pure, because it is three booleans and an integer arguing, and that is exactly the shape that
 * is wrong in a composable and provable in a test.
 */
fun nameLineCount(stepWraps: Boolean, narrow: Boolean, wanted: Int): Int {
    if (!stepWraps) return 1
    if (narrow) return 1
    return wanted.coerceIn(1, 4)
}

/**
 * Reach mode: the listing starts low on the screen, so the first rows sit under a thumb.
 *
 * ## Decision: a gap at the top, not a reversed list
 *
 * There are two ways to get the top of a listing within one-handed reach. One is to reverse the
 * list so it fills from the bottom up. The other is what One UI does with its large app bars: leave
 * the top of the screen empty, so the first row starts partway down and the empty space scrolls
 * away as you read. This is the second, and it must not be "simplified" into
 * `reverseLayout = true`:
 *
 *  - **A reversed tree is upside down.** Children land ABOVE the folder they belong to, and the
 *    guide lines - drawn from each row down to its next sibling - run the wrong way. The tree
 *    view is a large part of why this setting exists at all, so the setting cannot break it.
 *  - **Reversing fights the sort.** "A to Z" would read Z to A down the screen, so either the
 *    sort menu lies or it gets a second meaning in this mode. A gap leaves order, sort and the
 *    tree's geometry exactly as they are: nothing about the rows changes, only where the first
 *    one starts.
 *  - **The fresh tail would flip.** New arrivals are held at the end of the list, which in a
 *    reversed list is the top of the screen - the hard-to-reach end this mode exists to avoid.
 *
 * The gap is content padding, so it is part of what scrolls. It goes away as the reader scrolls
 * down and comes back only when they scroll back to the top, or on arriving in a new folder,
 * which starts at the top by design. It is never re-applied by a recomposition. Its height is
 * latched per folder and per width - see `FolderBody` - so a selection bar shrinking the
 * viewport cannot change it underneath the reader.
 *
 * A gap alone is not the feature: a list that starts low with tabs, back and the path bar still
 * along the top is still a two-handed screen. The pane header moves to the bottom of the pane
 * in the same mode, and the app's own bars move under the listing.
 */
object ThumbReach {

    /**
     * How much of the viewport is left empty above the first row.
     *
     * Half. In reach mode the app's bars are under the listing, so the viewport starts at the
     * status bar and runs most of the screen. On a 360 x 760dp phone that is a viewport of
     * about 560dp, and half of it puts the first row's top at about 300dp, two fifths of the
     * way down the screen. That is where One UI's large title ends, and about as high as a
     * thumb holding the phone travels comfortably. Two fifths of the viewport was tried on
     * paper first, and it lands the first row a third of the way down, which is still a
     * stretch.
     */
    const val GAP_FRACTION = 0.5f

    /**
     * Room for at least three rows under the gap, whatever the viewport is.
     *
     * Landscape on a phone leaves a viewport of about 250dp. Two fifths of that is reasonable
     * proportionally and leaves room for about two rows, which reads as a broken list rather
     * than a deliberate one.
     */
    const val KEEP_DP = 168f

    /** The gap in dp for a viewport [viewportDp] tall, or zero when the mode is off. */
    fun gapDp(viewportDp: Float, on: Boolean): Float {
        if (!on || viewportDp <= 0f) return 0f
        return (viewportDp * GAP_FRACTION)
            .coerceAtMost(viewportDp - KEEP_DP)
            .coerceAtLeast(0f)
    }
}
