package dev.niccc2007.filet.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Settings > Bottom bar: the bar as it is, rearranged by dragging along it; what is on it, in
 * order; and everything else, grouped as the side bar groups it.
 *
 * Two ways to change the order, one for each way of thinking about it. Along the bar, because the
 * bar is a row and its order is left to right: a long press lifts an entry and it is carried
 * sideways, the strip scrolling by itself when the entry is held against an end. And down the
 * list, by the grip, where each entry also has its name, its position and its remove. A screen
 * reader gets Move up and Move down on each row of the list, since neither drag can be done
 * without seeing it.
 */
@Composable
internal fun BarPicker(bar: List<BarItem>, write: (List<BarItem>) -> Unit) {
    Column {
        BarPreview(bar) { from, to -> write(bar.movedItem(from, to)) }
        OnTheBar(bar, write)
        NotOnTheBar(bar) { write(BottomBarConfig.toggled(bar, it)) }
    }
}

/** The tab icon mock's drawing for a bar entry, or null for an action that has none. */
internal fun barArt(item: BarItem): String? = item.tab?.art ?: if (item == BarItem.SEARCH) "search" else null

/** The real bar, drawn by the bar's own entries, that can be rearranged by dragging along it. */
@Composable
private fun BarPreview(bar: List<BarItem>, onMove: (Int, Int) -> Unit) {
    val colors = Filet.colors
    val haptics = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    val scrolls = BottomBarConfig.scrolls(bar.size)
    val centres = remember(bar) { mutableStateMapOf<Int, Float>() }
    val widths = remember(bar) { mutableStateMapOf<Int, Float>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else dragTarget(dragging, dragX, bar.indices.map { centres[it] ?: 0f })
    var landing by remember { mutableStateOf(false) }
    LaunchedEffect(landing) {
        if (landing) {
            withFrameNanos { }
            landing = false
        }
    }
    EdgeAutoScroll(active = dragging >= 0, scroll = scroll, heldAt = { dragX }, moved = { dragX += it })

    val entries: @Composable () -> Unit = {
        bar.forEachIndexed { i, item ->
            key(item) {
                PreviewEntry(
                    item = item,
                    lifted = i == dragging,
                    offsetX = shiftFor(i, dragging, target, dragX, centres, widths),
                    settle = i != dragging && !landing,
                    onCentre = { c, w -> centres[i] = c; widths[i] = w },
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragging = i
                        dragX = centres[i] ?: 0f
                    },
                    onDrag = { dragX += it },
                    onDragEnd = {
                        if (dragging >= 0 && target >= 0 && target != dragging) {
                            landing = true
                            onMove(dragging, target)
                        }
                        dragging = -1
                    },
                )
            }
        }
    }

    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)) {
            Text("YOUR BAR", fontSize = 9.5.sp, letterSpacing = 1.1.sp, color = colors.fg3, modifier = Modifier.weight(1f))
            Text(
                if (scrolls) "hold one, drag it sideways · scrolls" else "hold one, drag it sideways",
                fontSize = 9.5.sp,
                color = colors.fg3,
            )
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp))) {
            // The same two layouts the bar has: every entry a share of the width up to six, its
            // own width and a scroll past that.
            if (scrolls) {
                HScroll(
                    modifier = Modifier.background(colors.raised).padding(vertical = 4.dp),
                    ground = colors.raised,
                    state = scroll,
                    contentPadding = 6.dp,
                ) { entries() }
            } else {
                Row(
                    Modifier.fillMaxWidth().background(colors.raised).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) { entries() }
            }
        }
    }
}

/**
 * How far entry [i] stands from where it lives during a drag: the held one follows the finger, the
 * ones it passes step aside to open the gap it will land in. The tab strip's rule.
 */
private fun shiftFor(i: Int, dragging: Int, target: Int, at: Float, centres: Map<Int, Float>, sizes: Map<Int, Float>): Float = when {
    dragging < 0 -> 0f
    i == dragging -> at - (centres[i] ?: at)
    target < 0 -> 0f
    dragging < target && i > dragging && i <= target -> -(sizes[dragging] ?: 0f)
    dragging > target && i >= target && i < dragging -> sizes[dragging] ?: 0f
    else -> 0f
}

@Composable
private fun PreviewEntry(
    item: BarItem,
    lifted: Boolean,
    offsetX: Float,
    settle: Boolean,
    onCentre: (Float, Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Read live: pointerInput(Unit) runs its block once, and a callback captured then would see
    // the drop target as it was before the drag began. See TabChip.
    val start = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    val end = rememberUpdatedState(onDragEnd)
    val shift by animateFloatAsState(offsetX, animationSpec = tween(if (settle) 160 else 0), label = "bar-shift")
    val travel = if (settle) shift else offsetX
    Box(
        Modifier
            .onGloballyPositioned { onCentre(it.positionInParent().x + it.size.width / 2f, it.size.width.toFloat()) }
            .graphicsLayer {
                translationX = travel
                if (lifted) {
                    scaleX = 1.08f
                    scaleY = 1.08f
                    shadowElevation = 12f
                    shape = RoundedCornerShape(9.dp)
                    clip = false
                }
            }
            .zIndex(if (lifted) 1f else 0f)
            .background(if (lifted) Filet.colors.sel else Color.Transparent, RoundedCornerShape(9.dp)),
    ) {
        NavItem(
            barIcon(item),
            item.label,
            onClick = null,
            gesture = Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start.value() },
                    onDragEnd = { end.value() },
                    onDragCancel = { end.value() },
                ) { change, d ->
                    change.consume()
                    drag.value(d.x)
                }
            },
        )
    }
}

/** What is on the bar, in its order: a grip to drag, the icon, the name, the position, a remove. */
@Composable
private fun OnTheBar(bar: List<BarItem>, write: (List<BarItem>) -> Unit) {
    val centres = remember(bar) { mutableStateMapOf<Int, Float>() }
    val heights = remember(bar) { mutableStateMapOf<Int, Float>() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else dragTarget(dragging, dragY, bar.indices.map { centres[it] ?: 0f })
    var landing by remember { mutableStateOf(false) }
    LaunchedEffect(landing) {
        if (landing) {
            withFrameNanos { }
            landing = false
        }
    }
    Column {
        bar.forEachIndexed { i, item ->
            key(item) {
                BarListRow(
                    item = item,
                    position = i + 1,
                    lifted = i == dragging,
                    offsetY = shiftFor(i, dragging, target, dragY, centres, heights),
                    settle = i != dragging && !landing,
                    onCentre = { c, h -> centres[i] = c; heights[i] = h },
                    onDragStart = { dragging = i; dragY = centres[i] ?: 0f },
                    onDrag = { dragY += it },
                    onDragEnd = {
                        if (dragging >= 0 && target >= 0 && target != dragging) {
                            landing = true
                            write(bar.movedItem(dragging, target))
                        }
                        dragging = -1
                    },
                    onUp = if (i > 0) ({ write(bar.movedItem(i, i - 1)) }) else null,
                    onDown = if (i < bar.size - 1) ({ write(bar.movedItem(i, i + 1)) }) else null,
                    // The last one cannot be taken off. An empty bar is not a configuration, it is
                    // a screen with no way back to this one.
                    onRemove = if (bar.size > 1) ({ write(BottomBarConfig.toggled(bar, item)) }) else null,
                )
            }
        }
    }
}

@Composable
private fun BarListRow(
    item: BarItem,
    position: Int,
    lifted: Boolean,
    offsetY: Float,
    settle: Boolean,
    onCentre: (Float, Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val colors = Filet.colors
    val start = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    val end = rememberUpdatedState(onDragEnd)
    val shift by animateFloatAsState(offsetY, animationSpec = tween(if (settle) 160 else 0), label = "bar-row-shift")
    val travel = if (settle) shift else offsetY
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .onGloballyPositioned { onCentre(it.positionInParent().y + it.size.height / 2f, it.size.height.toFloat()) }
            .graphicsLayer {
                translationY = travel
                if (lifted) {
                    shadowElevation = 16f
                    shape = RoundedCornerShape(10.dp)
                    clip = false
                }
            }
            .zIndex(if (lifted) 1f else 0f)
            .background(if (lifted) colors.raised else Color.Transparent, RoundedCornerShape(10.dp))
            .semantics {
                customActions = listOfNotNull(
                    onUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                    onDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
                )
            }
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Only the grip starts a drag, so a thumb scrolling the page never picks a row up.
        Box(
            Modifier
                .size(width = 30.dp, height = 42.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start.value() },
                        onDragEnd = { end.value() },
                        onDragCancel = { end.value() },
                    ) { change, d ->
                        change.consume()
                        drag.value(d.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(FiletIcons.Grip, null, tint = colors.fg3, modifier = Modifier.size(16.dp))
        }
        BarItemIcon(item)
        Column(Modifier.weight(1f)) {
            Text(item.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text("Position $position", fontSize = 10.sp, color = colors.fg3)
        }
        if (onRemove != null) {
            Icon(
                FiletIcons.Close, "Take ${item.label} off the bar",
                tint = colors.fg3,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(9.dp),
            )
        } else {
            Spacer(Modifier.width(32.dp))
        }
    }
}

/** Everything not on the bar, in the side bar's groups, each added to the end with a tap. */
@Composable
private fun NotOnTheBar(bar: List<BarItem>, onAdd: (BarItem) -> Unit) {
    val colors = Filet.colors
    val off = BarItem.entries.filterNot { it in bar }
    if (off.isEmpty()) return
    Text(
        "NOT ON THE BAR · TAP TO ADD AT THE END",
        fontSize = 9.sp,
        letterSpacing = 1.3.sp,
        color = colors.fg3,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
    for (group in TabGroup.entries.map { it as TabGroup? } + null) {
        val items = off.filter { it.group == group }
        if (items.isEmpty()) continue
        dev.niccc2007.filet.shortcuts.GroupLabel(group?.label ?: "Actions")
        for (item in items) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable(onClickLabel = "Add to the bar") { onAdd(item) }
                    .padding(start = 36.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BarItemIcon(item)
                Text(item.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                Text("Add", fontSize = 11.sp, color = colors.accent, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

/** A bar entry's drawing, or its line glyph for an action that has no drawing. */
@Composable
private fun BarItemIcon(item: BarItem) {
    val art = barArt(item)
    if (art != null) {
        dev.niccc2007.filet.shortcuts.ArtTile(art, 30.dp)
    } else {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(barIcon(item), null, tint = Filet.colors.fg2, modifier = Modifier.size(18.dp))
        }
    }
}
