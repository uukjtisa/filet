package dev.niccc2007.filet.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.home.HomeArrange.DriveKind
import dev.niccc2007.filet.home.HomeArrange.Layout
import dev.niccc2007.filet.ui.tabs.Tiles
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.launch
import java.util.Locale

/** One tile in Home's drive row: its id, the group it belongs to, and whether it is taken off Home. */
internal data class DriveSlot(val id: String, val kind: DriveKind, val hidden: Boolean)

/** ease-out as CSS spells it, the mock's `.18s ease-out` and `.14s ease-out`. */
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** The mock's HOLD_MS: how long a drive is held before it lifts. */
private const val HOLD_MS = 350L

/**
 * Home's drives: one wrapping row, or a row per [DriveKind] under its name.
 *
 * In the edit state every drive is drawn, the hidden ones faint, each with an eye at its corner;
 * a drive held for [HOLD_MS] lifts and follows the finger, and passing over another drive moves it
 * there while the drives it passed slide out of its way. Grouped, a drive moves only within its own
 * group. The new order is handed to [onDrop] once, when the finger lets go - nothing is written
 * while the drag is still deciding.
 *
 * Outside the edit state this is just the rows, with [drawTile] doing what a tile does.
 *
 * @param slots in the order they are drawn; hidden ones only while [editing].
 * @param drawTile draws one tile; given whether it is being edited.
 */
@Composable
internal fun DriveRows(
    slots: List<DriveSlot>,
    layout: Layout,
    editing: Boolean,
    onEye: (String) -> Unit,
    onDrop: (List<String>) -> Unit,
    onLift: () -> Unit,
    drawTile: @Composable (DriveSlot, Boolean) -> Unit,
) {
    val byId = slots.associateBy { it.id }
    /** The order while a drag is deciding it; null when nothing is held. */
    var working by remember { mutableStateOf<List<String>?>(null) }
    var held by remember { mutableStateOf<String?>(null) }
    val order = working?.filter { it in byId }?.let { w -> w + slots.map { it.id }.filter { it !in w } } ?: slots.map { it.id }
    /** Each tile's place within this block, for finding the drive under the finger. */
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val container = remember { arrayOfNulls<LayoutCoordinates>(1) }

    val base = LocalViewConfiguration.current
    val config = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = HOLD_MS
        }
    }

    val group: @Composable (List<String>) -> Unit = { ids ->
        Tiles {
            for (id in ids) {
                key(id) {
                    tile {
                        DriveTile(
                            slot = byId.getValue(id),
                            editing = editing,
                            held = held == id,
                            container = container,
                            onBounds = { r -> bounds[id] = r },
                            onLift = {
                                held = id
                                working = order
                                onLift()
                            },
                            onFinger = { finger ->
                                val now = working ?: return@DriveTile
                                val over = bounds.entries.firstOrNull { (k, r) -> k != id && k in byId && r.contains(finger) }?.key
                                    ?: return@DriveTile
                                if (!HomeArrange.canMove(layout, byId.getValue(id).kind, byId.getValue(over).kind)) return@DriveTile
                                val next = HomeArrange.passed(now, id, over)
                                if (next != now) working = next
                            },
                            onLetGo = {
                                val done = working
                                held = null
                                working = null
                                if (done != null && done != slots.map { it.id }) onDrop(done)
                            },
                            onEye = { onEye(id) },
                            draw = drawTile,
                        )
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides if (editing) config else base) {
        Column(Modifier.fillMaxWidth().onGloballyPositioned { container[0] = it }) {
            when (layout) {
                Layout.FLOW -> group(order)
                Layout.GROUPED -> for (kind in DriveKind.entries) {
                    val ids = order.filter { byId[it]?.kind == kind }
                    if (ids.isEmpty()) continue
                    // The group being dragged in draws over the ones after it, so a drive pulled
                    // past its group's edge is not drawn under the next group's tiles.
                    key(kind) {
                    Box(Modifier.zIndex(if (held != null && byId[held!!]?.kind == kind) 1f else 0f)) {
                        Column {
                            Text(
                                kind.title.uppercase(Locale.US),
                                fontSize = 9.sp,
                                letterSpacing = 0.8.sp,
                                color = Filet.colors.fg3,
                                modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 6.dp),
                            )
                            group(ids)
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * One drive, with the edit state's eye, lift and slide.
 *
 * Two boxes on purpose. The outer one is laid out and takes the finger; the inner one is moved
 * by `graphicsLayer`. Where a tile is in the row and where it is drawn are then two separate
 * answers, so the finger's position stays measured against the slot, and the slide from the old
 * slot to the new one (the mock's FLIP) never feeds back into the position it was measured from.
 */
@Composable
private fun DriveTile(
    slot: DriveSlot,
    editing: Boolean,
    held: Boolean,
    container: Array<LayoutCoordinates?>,
    onBounds: (Rect) -> Unit,
    onLift: () -> Unit,
    onFinger: (Offset) -> Unit,
    onLetGo: () -> Unit,
    onEye: () -> Unit,
    draw: @Composable (DriveSlot, Boolean) -> Unit,
) {
    val colors = Filet.colors
    val scope = rememberCoroutineScope()
    /** Where the drawing is relative to the slot: the finger while held, the slide otherwise. */
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val last = remember { arrayOfNulls<Offset>(1) }
    val grab = remember { arrayOf(Offset.Zero) }
    val lift by animateFloatAsState(if (held) 1f else 0f, tween(140, easing = EaseOut), label = "lift")
    val lifted by rememberUpdatedState(onLift)
    val finger by rememberUpdatedState(onFinger)
    val letGo by rememberUpdatedState(onLetGo)
    val isHeld by rememberUpdatedState(held)
    val shape = RoundedCornerShape(13.dp)

    Box(
        Modifier
            .zIndex(if (held) 1f else 0f)
            // After every layout pass, parents first, so the block's own coordinates are already
            // there when a tile asks for its place within it. A scroll moves the block and its
            // tiles together, so it never reads as a tile having moved.
            .onGloballyPositioned { c ->
                val box = container[0] ?: return@onGloballyPositioned
                if (!box.isAttached) return@onGloballyPositioned
                val at = box.localPositionOf(c, Offset.Zero)
                onBounds(Rect(at, c.size.toSize()))
                val before = last[0]
                last[0] = at
                if (before == null || before == at) return@onGloballyPositioned
                // The slot moved. Start the drawing where it was and let it travel; a held tile
                // only keeps its place under the finger, which the next move event then takes on.
                scope.launch {
                    shift.snapTo(shift.value + (before - at))
                    if (!isHeld) shift.animateTo(Offset.Zero, tween(180, easing = EaseOut))
                }
            }
            .then(
                if (!editing) Modifier
                else Modifier.pointerInput(slot.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at ->
                            grab[0] = at
                            lifted()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            scope.launch { shift.snapTo(change.position - grab[0]) }
                            val box = last[0] ?: return@detectDragGesturesAfterLongPress
                            finger(box + change.position)
                        },
                        onDragEnd = {
                            letGo()
                            scope.launch { shift.animateTo(Offset.Zero, tween(180, easing = EaseOut)) }
                        },
                        onDragCancel = {
                            letGo()
                            scope.launch { shift.animateTo(Offset.Zero, tween(180, easing = EaseOut)) }
                        },
                    )
                },
            ),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier.graphicsLayer {
                translationX = shift.value.x
                translationY = shift.value.y
                val s = 1f + 0.04f * lift
                scaleX = s
                scaleY = s
                shadowElevation = 10.dp.toPx() * lift
                this.shape = shape
                clip = false
            },
        ) {
            draw(slot, editing)
            if (lift > 0f) {
                Box(Modifier.matchParentSize().border(1.dp, colors.accent.copy(alpha = lift), shape))
            }
        }
        if (editing && !held) {
            // At the corner, half outside it, as the mock draws it: on the tile it belongs to,
            // and clear of the tile's own words.
            Box(
                Modifier
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.high)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClick = onEye),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (slot.hidden) FiletIcons.EyeOff else FiletIcons.Eye,
                    if (slot.hidden) "Show this drive on Home" else "Hide this drive from Home",
                    tint = if (slot.hidden) colors.fg3 else colors.fg2,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
