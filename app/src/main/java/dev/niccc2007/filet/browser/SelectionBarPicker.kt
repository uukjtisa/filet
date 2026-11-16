package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.HScroll
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Settings > Selection bar, built the way the bottom bar's editor is: the pill as it will look,
 * the buttons on it in order, each with its icon, a grip to slide it and a cross, then everything
 * behind More with an Add, in the More card's two groups.
 *
 * The list it replaces had up and down buttons and no icons, so finding a button meant reading
 * every name, and moving one to the front meant pressing up once per place.
 */
@Composable
internal fun SelectionBarPicker(bar: List<String>, write: (List<String>) -> Unit) {
    Column {
        PillPreview(bar)
        OnTheSelectionBar(bar, write)
        InMore(bar) { write(SelectionBarConfig.toggled(bar, it)) }
    }
}

/** The glyph a selection action is drawn with, as the bar and the menus draw it. */
internal fun selectionIcon(id: String): ImageVector = when (id) {
    "copy", "copypath" -> FiletIcons.Copy
    "move" -> FiletIcons.Cut
    "rename" -> FiletIcons.Rename
    "delete" -> FiletIcons.Delete
    "send" -> FiletIcons.Share
    "compress" -> FiletIcons.Zip
    "openwith" -> FiletIcons.Open
    "foldericon" -> FiletIcons.FolderOpen
    "details" -> FiletIcons.Info
    "bookmark", "gallery" -> FiletIcons.Star
    "share" -> FiletIcons.ShareNodes
    "shortcut" -> FiletIcons.Home
    "install" -> FiletIcons.Apk
    "metadata" -> FiletIcons.Cog
    "invert" -> FiletIcons.Check
    else -> FiletIcons.More
}

/** The More card's second group: what puts the selection somewhere. Everything else acts on it. */
private val PUT_SOMEWHERE = setOf("bookmark", "share", "shortcut")

/** The pill as it will look over a selection of three, More at the end. */
@Composable
private fun PillPreview(bar: List<String>) {
    val colors = Filet.colors
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.sunken)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
            .padding(8.dp),
    ) {
        Text(
            "YOUR BAR",
            fontSize = 9.5.sp,
            letterSpacing = 1.1.sp,
            color = colors.fg3,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            Modifier
                .clip(PillShape)
                .background(colors.raised)
                .border(1.dp, colors.lineSoft, PillShape)
                .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "3",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                modifier = Modifier.padding(start = 10.dp, end = 6.dp),
            )
            Icon(FiletIcons.Close, null, tint = colors.fg2, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f, fill = false).padding(start = 6.dp)) {
                HScroll(ground = colors.raised, spacing = 6.dp) {
                    for (id in bar) {
                        FootButton(selectionIcon(id), SelectionBarConfig.labelFor(id) ?: id, onClick = {})
                    }
                }
            }
            Icon(FiletIcons.More, null, tint = colors.fg2, modifier = Modifier.padding(horizontal = 8.dp).size(18.dp))
        }
    }
}

/** What is on the bar, in its order: a grip to slide, the icon, the name, the position, a cross. */
@Composable
private fun OnTheSelectionBar(bar: List<String>, write: (List<String>) -> Unit) {
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
        bar.forEachIndexed { i, id ->
            key(id) {
                val label = SelectionBarConfig.labelFor(id) ?: id
                GripRow(
                    label = label,
                    sub = "Position ${i + 1}",
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
                    removeLabel = "Take $label off the bar",
                    // The last one stays. A bar holding only More and a count reads as a failure
                    // to load rather than as a choice.
                    onRemove = if (bar.size > 1) ({ write(SelectionBarConfig.toggled(bar, id)) }) else null,
                ) { Glyph(id) }
            }
        }
    }
}

/** Everything behind More, in the More card's groups, each added to the end with a tap. */
@Composable
private fun InMore(bar: List<String>, onAdd: (String) -> Unit) {
    val colors = Filet.colors
    val off = SelectionBarConfig.CHOOSABLE.filterNot { it.first in bar }
    if (off.isEmpty()) return
    // Full means no add at all, and the heading says why: an add that silently pushed the first
    // one off would be a switch that turns another one off.
    val full = SelectionBarConfig.full(bar)
    Text(
        if (full) "IN MORE · THE BAR IS FULL, TAKE ONE OFF TO ADD" else "IN MORE · TAP TO ADD AT THE END",
        fontSize = 9.sp,
        letterSpacing = 1.3.sp,
        color = colors.fg3,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
    for ((title, place) in listOf("This file" to false, "Put somewhere" to true)) {
        val items = off.filter { (it.first in PUT_SOMEWHERE) == place }
        if (items.isEmpty()) continue
        dev.niccc2007.filet.shortcuts.GroupLabel(title)
        for ((id, label) in items) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable(enabled = !full, onClickLabel = "Add to the bar") { onAdd(id) }
                    .padding(start = 36.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Glyph(id)
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (full) colors.fg3 else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (!full) Text("Add", fontSize = 11.sp, color = colors.accent, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

@Composable
private fun Glyph(id: String) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Icon(selectionIcon(id), null, tint = Filet.colors.fg2, modifier = Modifier.size(18.dp))
    }
}
