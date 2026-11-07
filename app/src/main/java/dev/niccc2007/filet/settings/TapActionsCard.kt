package dev.niccc2007.filet.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.Tap
import dev.niccc2007.filet.browser.TapPlan
import dev.niccc2007.filet.browser.TapView
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.theme.Filet

/**
 * One view's tap set (#34): a row naming the view and its preset, which unfolds to the preset's
 * drop-down and the table of what each tap does to a file and to a folder.
 *
 * Folded by default, so the section is three lines until somebody wants to change one - the
 * table is twelve drop-downs per view, and three of those open at once would be a wall.
 *
 * @param extra drawn at the end of the unfolded table, for a setting that only means something
 *   in this view - the tree's Back.
 */
@Composable
internal fun TapSetRow(title: String, view: TapView, prefs: Prefs, extra: @Composable () -> Unit = {}) {
    val colors = Filet.colors
    val stored by prefs.tapStored(view).collectAsState()
    val set = remember(view, stored) { TapPlan.decode(view, stored) }
    val preset = TapPlan.presetOf(view, set)
    var open by rememberSaveable(view) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", fontSize = 12.sp, color = colors.fg3)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, color = colors.fg2, modifier = Modifier.weight(1f))
            Text(preset.label, fontSize = 11.5.sp, color = colors.fg3)
        }
        if (!open) return@Column

        Column(Modifier.padding(start = 34.dp, end = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preset", fontSize = 11.5.sp, color = colors.fg2, modifier = Modifier.width(84.dp))
                Pick(
                    current = preset.label,
                    // Custom is where a changed row lands, not something to pick.
                    options = TapPlan.presets(view).map { it.label },
                ) { i ->
                    val p = TapPlan.presets(view)[i]
                    prefs.setTap(view, TapPlan.encode(TapPlan.preset(view, p)!!))
                }
            }
            Spacer(Modifier.height(6.dp))
            // The gallery holds no folders, so it has one column.
            val sides = if (view == TapView.GALLERY) listOf(false) else listOf(false, true)
            Row {
                Spacer(Modifier.width(84.dp))
                for (isDir in sides) {
                    Text(
                        if (isDir) "Folder" else "File",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            for (tap in Tap.entries) {
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tap.label, fontSize = 11.5.sp, color = colors.fg2, modifier = Modifier.width(84.dp))
                    for (isDir in sides) {
                        val choices = TapPlan.choices(view, isDir)
                        Box(Modifier.weight(1f).padding(end = 6.dp)) {
                            Pick(set.of(isDir, tap).label, choices.map { it.label }) { i ->
                                prefs.setTap(view, TapPlan.encode(set.with(isDir, tap, choices[i])))
                            }
                        }
                    }
                }
            }
            if (view != TapView.GALLERY) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "While something is selected, a tap toggles it and two taps open the menu, whatever is set here.",
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    lineHeight = 14.sp,
                )
            }
        }
        extra()
    }
}

/** A value that opens a list of the others: the settings' drop-down. */
@Composable
private fun Pick(current: String, options: List<String>, onPick: (Int) -> Unit) {
    val colors = Filet.colors
    var shown by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, colors.lineSoft, RoundedCornerShape(7.dp))
                .clickable { shown = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                current,
                fontSize = 11.sp,
                color = colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text("▾", fontSize = 10.sp, color = colors.fg3)
        }
        DropdownMenu(expanded = shown, onDismissRequest = { shown = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 12.sp) },
                    onClick = {
                        shown = false
                        onPick(i)
                    },
                )
            }
        }
    }
}

