package dev.niccc2007.filet.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.niccc2007.filet.data.SectionLayout
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.theme.Filet

/** One card a surface can rearrange: its id, what it is called, and whether it may be hidden. */
data class SectionSpec(val id: String, val title: String, val hideable: Boolean = true)

/**
 * What holding a card's heading offers: rearranging the surface, or taking this card off it.
 *
 * Only headings. A file inside a card keeps its own menu, so a hold means the same thing it
 * always did everywhere except on the one line that names the card.
 */
@Composable
fun SectionMenu(spec: SectionSpec, onRearrange: () -> Unit, onHide: () -> Unit, onClose: () -> Unit) {
    Dlg(onDismiss = onClose) {
        DlgHeader(FiletIcons.Menu, spec.title, onClose = onClose)
        DlgBody {
            DlgPick(FiletIcons.Sort, "Rearrange") { onClose(); onRearrange() }
            if (spec.hideable) DlgPick(FiletIcons.EyeOff, "Hide") { onClose(); onHide() }
        }
    }
}

/**
 * A surface's cards, one row each, to reorder by dragging and to hide or bring back.
 *
 * The same editor for Home and for the side bar, so the two cannot learn different rules. A row
 * is a handle, the card's name and its eye. Hidden cards sit dimmed at the end, and their eye
 * puts them back. A card that is not [SectionSpec.hideable] has no eye at all.
 *
 * Dragging moves the held row with the finger while the rows it passes slide out of its way,
 * the way the tab strip's drag does, and the drop is one [onChange]. Done and Back both leave.
 *
 * A card can carry its own settings, drawn under its row and moving with it - how many rows
 * Home's New files shows, say. One slot for every surface, so a card's options are never a
 * special case wired into the editor; rows are then not all one height, and [SectionDrag]
 * measures the ones they are.
 *
 * @param specs every card the surface draws; [layout] decides their order.
 * @param settings by card id, what is drawn under that card's row while it is showing.
 */
@Composable
fun SectionEditor(
    specs: List<SectionSpec>,
    layout: SectionLayout,
    locked: Set<String>,
    onChange: (SectionLayout) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    settings: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    BackHandler(onBack = onDone)
    val colors = Filet.colors
    val byId = specs.associateBy { it.id }
    val visible = layout.visible.filter { it in byId }
    val hidden = layout.hiddenOnes.filter { it in byId }
    val rowPx = with(LocalDensity.current) { ROW_DP.dp.toPx() }
    /** Each showing card's height with its settings, as last laid out. */
    val heightOf = remember { mutableStateMapOf<String, Float>() }
    val heights = visible.map { heightOf[it] ?: rowPx }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val target = dragging?.let { SectionDrag.target(heights, it, dragDy) }
    val heldPx = dragging?.let { heights.getOrNull(it) } ?: rowPx
    val current = rememberUpdatedState(layout)
    val sizes = rememberUpdatedState(heights)
    val change = rememberUpdatedState(onChange)
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Rearrange", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                "Done",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        visible.forEachIndexed { i, id ->
            key(id) {
                val spec = byId.getValue(id)
                // Where this row sits while another is dragged past it. Snapped home the moment
                // the drop lands, because the drop has already moved it there in the layout.
                val shift = remember { Animatable(0f) }
                val want = when {
                    dragging == null || i == dragging || target == null -> 0f
                    dragging!! < i && i <= target -> -heldPx
                    dragging!! > i && i >= target -> heldPx
                    else -> 0f
                }
                LaunchedEffect(want, dragging) { if (dragging == null) shift.snapTo(0f) else shift.animateTo(want) }
                val index by rememberUpdatedState(i)
                Column(
                    Modifier
                        .zIndex(if (i == dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (i == dragging) dragDy else shift.value
                            shadowElevation = if (i == dragging) 6.dp.toPx() else 0f
                        }
                        .background(colors.sunken)
                        .onSizeChanged { heightOf[id] = it.height.toFloat() },
                ) {
                    EditorRow(
                        spec = spec,
                        showing = true,
                        canHide = spec.hideable && id !in locked,
                        modifier = Modifier,
                        handle = Modifier.pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { dragging = index; dragDy = 0f },
                                onDrag = { change, delta -> change.consume(); dragDy += delta.y },
                                onDragEnd = {
                                    val from = dragging
                                    val to = from?.let { SectionDrag.target(sizes.value, it, dragDy) }
                                    if (from != null && to != null && to != from) change.value(current.value.move(from, to))
                                    dragging = null
                                    dragDy = 0f
                                },
                                onDragCancel = { dragging = null; dragDy = 0f },
                            )
                        },
                        onEye = { onChange(current.value.hide(id, locked)) },
                    )
                    settings[id]?.invoke()
                }
            }
        }
        if (hidden.isNotEmpty()) {
            SectionLabel("Hidden")
            for (id in hidden) {
                key(id) {
                    EditorRow(
                        spec = byId.getValue(id),
                        showing = false,
                        canHide = true,
                        modifier = Modifier.alpha(0.5f),
                        handle = null,
                        onEye = { onChange(current.value.show(id)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EditorRow(
    spec: SectionSpec,
    showing: Boolean,
    canHide: Boolean,
    modifier: Modifier,
    handle: Modifier?,
    onEye: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_DP.dp)
            .background(colors.sunken)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).then(handle ?: Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (handle != null) {
                Icon(FiletIcons.Menu, "Drag to reorder ${spec.title}", tint = colors.fg3, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(spec.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (canHide) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEye),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (showing) FiletIcons.Eye else FiletIcons.EyeOff,
                    if (showing) "Hide ${spec.title}" else "Show ${spec.title}",
                    tint = if (showing) colors.fg2 else colors.fg3,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One editor row: tall enough for a thumb on the handle. A card's settings add to it. */
private const val ROW_DP = 48

/**
 * Where a dragged card lands among cards of different heights. Pure, so it is a test.
 *
 * A card is passed once the held card's leading edge crosses its middle. With equal rows that is
 * plain rounding of the distance over the row height - what the editor did before cards could
 * carry settings - and with a tall card it still asks for half of it, not half of a row.
 */
internal object SectionDrag {

    /** The index the card at [from] lands on, dragged [dy] px, among cards [heights] tall. */
    fun target(heights: List<Float>, from: Int, dy: Float): Int {
        if (from !in heights.indices) return from.coerceIn(0, maxOf(0, heights.lastIndex))
        val tops = heights.runningFold(0f) { at, h -> at + h }
        var to = from
        if (dy > 0f) {
            val lead = tops[from] + heights[from] + dy
            for (j in from + 1..heights.lastIndex) if (lead >= tops[j] + heights[j] / 2f) to = j else break
        } else if (dy < 0f) {
            val lead = tops[from] + dy
            for (j in from - 1 downTo 0) if (lead <= tops[j] + heights[j] / 2f) to = j else break
        }
        return to
    }
}
