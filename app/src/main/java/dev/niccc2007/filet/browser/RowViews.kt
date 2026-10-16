package dev.niccc2007.filet.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.ui.Motion
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
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
 * @param showDate whether there is ROOM for a separate date column. Whether one is WANTED is
 *   [EntryDisplay.dateColumn], and both have to agree - width is a constraint, the setting is a
 *   choice, and collapsing them into one flag is how a preference became unreachable on a phone.
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
    /** Carried so the things that size themselves against the pane can ask. See [TreeScale]. */
    val widthDp: Int = 0,
) {
    companion object {
        fun forWidth(widthDp: Int, requested: ViewStep) = PaneMetrics(
            widthDp = widthDp,
            step = if (widthDp < 270 && requested.isGrid) ViewStep.LIST
            else if (widthDp < 200) ViewStep.COMPACT_LIST
            else requested,
            showDate = widthDp >= 330,
            showSize = widthDp >= 180,
            showChips = widthDp >= 180,
            oneLineName = widthDp < 300,
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val colors = Filet.colors
    val accent = colors.accent
    val kind = FileKind.of(node)
    val bg by animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            else -> Color.Transparent
        },
        animationSpec = Motion.fast(),
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
    val framed by animateFloatAsState(
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
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            .then(modifier)
            .drawBehind {
                if (treeRow) treeGuides(depth, spines, lastOfSiblings, openInPlace, steps, accent)
                heldEdge?.takeIf { framed > 0.01f }?.let { activeFrame(it, accent, framed) }
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
            val turn by animateFloatAsState(
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
                    .graphicsLayer { rotationZ = turn },
            )
        } else if (treeRow || depth > 0) {
            // The chevron's width is reserved whether or not there is one. Without this a
            // file, or a folder with nothing in it, sat a chevron to the left of everything
            // around it - which reads as the list being broken rather than as that row being
            // different.
            Spacer(Modifier.width(26.dp))
        }
        FileThumb(
            node = node,
            size = step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
            enabled = thumbnails,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                fontSize = step.nameSize.sp,
                lineHeight = (step.nameSize + 4).sp,
                maxLines = if (step.twoLine && !metrics.oneLineName) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.twoLine && display.subtitle) {
                Text(
                    text = EntryFormat.subtitle(node.isDir, node.size, node.mtime, display),
                    fontSize = step.metaSize.sp,
                    color = colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // ONE column for "how big is this". A file answers in bytes and a folder in items, which
        // is the same question in different units - and the folder half is what used to be a
        // permanent dash.
        if (metrics.showSize && display.measure) {
            val measure = EntryFormat.measure(node.isDir, node.size, count)
            if (measure.isNotEmpty()) {
                Text(
                    text = measure,
                    fontSize = step.metaSize.sp,
                    // The list face with tabular figures, not Cascadia Mono. A code font in a
                    // list of documents was the complaint; what the column actually needs is
                    // digits that line up, which is a font FEATURE rather than a whole family.
                    fontFamily = null,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                    color = colors.fg3,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(if (node.isDir) 68.dp else 62.dp),
                )
            } else {
                Spacer(Modifier.width(if (node.isDir) 68.dp else 62.dp))
            }
        }
        // Only when there is room AND it was asked for. Off by default: with the subtitle on it
        // prints the same date again a few pixels to the right, which is the duplication reported.
        if (metrics.showDate && display.dateColumn) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = EntryFormat.date(node.mtime, display),
                fontSize = step.metaSize.sp,
                fontFamily = null,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                color = colors.fg3,
                maxLines = 1,
                modifier = Modifier.width(92.dp),
            )
        }
    }
}

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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDoubleClick: () -> Unit = {},
) {
    val colors = Filet.colors
    val kind = FileKind.of(node)
    val step = metrics.step
    val bg by animateColorAsState(
        targetValue = when {
            dropTarget -> colors.drop
            selected -> colors.sel
            else -> Color.Transparent
        },
        animationSpec = Motion.fast(),
        label = "tileBackground",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        // The drag detector goes last - see the note in FileRow. Same trap, same fix.
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
            .then(modifier)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        FileThumb(
            node = node,
            size = step.icon.dp,
            fallbackTint = iconTint(kind, colors.accent, MaterialTheme.colorScheme.onSurfaceVariant, colors.fg2),
            enabled = thumbnails,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = node.name,
            fontSize = step.nameSize.sp,
            lineHeight = (step.nameSize + 3).sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Folders deliberately print nothing here: a bare em dash where a size would be
        // reads as a failed lookup rather than as "not applicable".
        if (!node.isDir && metrics.showSize) {
            Text(humanSize(node.size), fontSize = step.metaSize.sp, color = colors.fg3)
        }
    }
}

/** A hit inside a search result list: name plus where it was found. */
@Composable
fun SearchResultRow(
    node: VNode,
    where: String,
    /**
     * Whether the index has been re-checked for this entry by the pass that is running.
     *
     * Two states and no more. A row that cannot be justified is dropped from the list rather
     * than labelled - see HitState, where the rejected third state is written down.
     */
    state: dev.niccc2007.filet.index.HitState = dev.niccc2007.filet.index.HitState.AVAILABLE,
    /** Whether a pass is running. Between passes no row carries a badge. See showBadge. */
    crawlRunning: Boolean = false,
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
            Text(node.name, fontSize = metrics.step.nameSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // While a pass is running, every row says which it is: only drawing the
                // cautious half made the confident half indistinguishable from a feature that
                // was not there - and on a FIRST index nothing can be cautious, because the
                // database was empty when the pass began. That is why the screen was blank.
                // Between passes nothing is drawn; everything is available then.
                if (dev.niccc2007.filet.index.showBadge(crawlRunning)) {
                    val confirming = state == dev.niccc2007.filet.index.HitState.CONFIRMING
                    val tint = if (confirming) colors.warn else colors.good
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

/**
 * Which part of the outline round the working folder a row is responsible for.
 *
 * A lazy list cannot draw one box across several of its items - there is no "several of its
 * items" to hang a modifier on, and an overlay computed from item offsets is a frame wrong
 * every time anything scrolls, resizes or is inserted. So the box is drawn in pieces: every
 * row in the block draws its own two sides, the first adds the top and its corners, the
 * last adds the bottom and its corners, and because the rows are adjacent the pieces meet.
 * It then scrolls, recycles and clips for free, and a block whose top has scrolled off
 * shows an open-topped box - which is the right picture rather than a bug.
 */
enum class ActiveEdge { TOP, MIDDLE, BOTTOM, ONLY }

/**
 * The outline round the folder being worked in, and everything inside it.
 *
 * A border, not a wash. A filled tint was built first and it reads as a SELECTION, which is
 * a different and already-taken meaning in this list. A box says "this is the area you are
 * in", which is the thing being said - and is what X-plore draws for the same reason.
 *
 * Three strokes, widest and faintest first, so the edge carries a glow instead of being a
 * hairline rectangle sitting on top of the list. At that alpha the outer pass is barely a
 * colour; it is the whole difference between a drawn border and a stuck-on one.
 */
private fun DrawScope.activeFrame(edge: ActiveEdge, accent: Color, at: Float) {
    // From a little outside to its resting place. A box that only fades in reads as a
    // transparency change; one that also closes reads as something arriving.
    val inset = (3f + 5f * (1f - at)).dp.toPx()
    val r = 12.dp.toPx()
    val left = inset
    val right = size.width - inset
    val capTop = edge == ActiveEdge.TOP || edge == ActiveEdge.ONLY
    val capBottom = edge == ActiveEdge.BOTTOM || edge == ActiveEdge.ONLY
    val top = if (capTop) inset else 0f
    val bottom = if (capBottom) size.height - inset else size.height
    // The sides stop short of whichever end turns a corner, and run to the row's own edge
    // at the other - that butt join with the next row's side is what makes the pieces read
    // as one continuous line.
    val sideTop = if (capTop) top + r else top
    val sideBottom = if (capBottom) bottom - r else bottom
    val box = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)

    for ((width, alpha) in FRAME_PASSES) {
        val colour = accent.copy(alpha = alpha * at)
        val w = width.dp.toPx()
        val stroke = Stroke(width = w)
        drawLine(colour, Offset(left, sideTop), Offset(left, sideBottom), w)
        drawLine(colour, Offset(right, sideTop), Offset(right, sideBottom), w)
        if (capTop) {
            drawLine(colour, Offset(left + r, top), Offset(right - r, top), w)
            drawArc(colour, 180f, 90f, false, Offset(left, top), box, style = stroke)
            drawArc(colour, 270f, 90f, false, Offset(right - r * 2f, top), box, style = stroke)
        }
        if (capBottom) {
            drawLine(colour, Offset(left + r, bottom), Offset(right - r, bottom), w)
            drawArc(colour, 0f, 90f, false, Offset(right - r * 2f, bottom - r * 2f), box, style = stroke)
            drawArc(colour, 90f, 90f, false, Offset(left, bottom - r * 2f), box, style = stroke)
        }
    }
}

/** Stroke widths and alphas, outside in. The first two are the glow; the last is the line. */
private val FRAME_PASSES = listOf(6f to 0.07f, 3f to 0.16f, 1.4f to 0.72f)

/**
 * How long the outline takes to arrive.
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
 * Reported as confusing, and it is: four rows at the same indent could be children of four
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
