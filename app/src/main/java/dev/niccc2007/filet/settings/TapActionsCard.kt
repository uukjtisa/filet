package dev.niccc2007.filet.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.Tap
import dev.niccc2007.filet.browser.TapDo
import dev.niccc2007.filet.browser.TapPlan
import dev.niccc2007.filet.browser.TapPreset
import dev.niccc2007.filet.browser.TapView
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.dialogs.PopMenu
import dev.niccc2007.filet.ui.dialogs.PopRow
import dev.niccc2007.filet.ui.theme.Filet

/** The sub-row indent the rest of Settings uses, for what sits under a row. */
private val UNDER = 30.dp

/**
 * One view's tap set (#34): a Settings row naming the view and its preset, which unfolds to the
 * preset chips and the table of what each tap does to a file and to a folder.
 *
 * Folded by default, so the section is three rows until somebody wants to change one - the
 * table is up to eight choices per view, and three open at once would be a wall.
 *
 * Drawn in the page's own parts rather than Material's: the header is a Settings row, the preset
 * is the chip row every other choice uses, and a value opens the app's [PopMenu]. The first cut
 * used text arrows and Material's `DropdownMenu`, and read as a screen from another app.
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
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "tapSetChevron")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (preset == TapPreset.CUSTOM) "Custom" else "${preset.label} preset",
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                    lineHeight = 14.sp,
                )
            }
            Icon(
                FiletIcons.Chevron,
                if (open) "Fold" else "Unfold",
                tint = colors.fg3,
                modifier = Modifier.size(16.dp).rotate(turn),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                val presets = TapPlan.presets(view)
                // Custom shows as chosen once a row has been changed, and is not offered: it is
                // where a change lands, not something to pick.
                val chips = presets.map { it.label } + if (preset == TapPreset.CUSTOM) listOf("Custom") else emptyList()
                SegmentRow(
                    "Preset",
                    chips,
                    selected = if (preset == TapPreset.CUSTOM) presets.size else presets.indexOf(preset),
                    sub = true,
                ) { i ->
                    if (i < presets.size) prefs.setTap(view, TapPlan.encode(TapPlan.preset(view, presets[i])!!))
                }
                // The gallery holds no folders, so it has one column.
                val sides = if (view == TapView.GALLERY) listOf(false) else listOf(false, true)
                Row(Modifier.padding(start = UNDER, end = 14.dp, top = 4.dp, bottom = 2.dp)) {
                    Spacer(Modifier.width(LABEL))
                    for (isDir in sides) {
                        Text(
                            if (isDir) "Folder" else "File",
                            fontSize = 10.5.sp,
                            color = colors.fg3,
                            modifier = Modifier.weight(1f).padding(start = 2.dp),
                        )
                    }
                }
                for (tap in Tap.entries) {
                    Row(
                        Modifier.padding(start = UNDER, end = 14.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tap.label,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(LABEL),
                        )
                        for ((n, isDir) in sides.withIndex()) {
                            if (n > 0) Spacer(Modifier.width(6.dp))
                            Box(Modifier.weight(1f)) {
                                Choice(set.of(isDir, tap), TapPlan.choices(view, isDir)) { act ->
                                    prefs.setTap(view, TapPlan.encode(set.with(isDir, tap, act)))
                                }
                            }
                        }
                    }
                }
                if (view != TapView.GALLERY) {
                    Text(
                        "While something is selected, a tap toggles it and two taps open the menu, whatever is set here.",
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(start = UNDER, end = 14.dp, top = 6.dp, bottom = 4.dp),
                    )
                }
                extra()
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** How wide the tap names' column is: "Three taps" at the row's size, with room to breathe. */
private val LABEL = 78.dp

/**
 * One cell of the table: the action, as a pill the size of a chip, opening the app's menu with
 * every choice the cell allows and a tick on the one in use.
 */
@Composable
private fun Choice(current: TapDo, choices: List<TapDo>, onPick: (TapDo) -> Unit) {
    val colors = Filet.colors
    var shown by remember { mutableStateOf(false) }
    val quiet = current == TapDo.NOTHING
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (quiet) Color.Transparent else colors.raised)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp))
                .clickable { shown = true }
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconOf(current), null, tint = if (quiet) colors.fg3 else colors.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                current.label,
                fontSize = 11.5.sp,
                color = if (quiet) colors.fg3 else colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(FiletIcons.Down, null, tint = colors.fg3, modifier = Modifier.size(12.dp))
        }
        PopMenu(expanded = shown, onDismiss = { shown = false }, below = 32.dp) {
            for (act in choices) {
                PopRow(iconOf(act), act.label, active = act == current, checked = act == current) {
                    shown = false
                    onPick(act)
                }
            }
        }
    }
}

private fun iconOf(act: TapDo): ImageVector = when (act) {
    TapDo.OPEN -> FiletIcons.Open
    TapDo.FOCUS -> FiletIcons.Mark
    TapDo.SELECT -> FiletIcons.Check
    TapDo.EXPAND -> FiletIcons.Down
    TapDo.MENU -> FiletIcons.More
    TapDo.PEEK -> FiletIcons.Eye
    TapDo.OPEN_LOCATION -> FiletIcons.FolderOpen
    TapDo.OPEN_WITH -> FiletIcons.Link
    TapDo.DETAILS -> FiletIcons.Info
    TapDo.NOTHING -> FiletIcons.Close
}
