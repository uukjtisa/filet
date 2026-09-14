package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The right-click menu, as a phone gesture.
 *
 * Nic asked for *"the context menu for like when i hold click for an item.. like in file explorer
 * right clicking"*, with *"a familiarity of windows 11 context right click menu in the explorer"*
 * blended into this app's theme.
 *
 * The distinctive thing about that menu is not its corner radius, it is the **row of icon-only
 * verbs across the top**. Cut, copy, rename, share, delete are the five things anybody actually
 * does, and putting them on one line means the common case is a single tap at a fixed position
 * rather than a read down a list. Everything else is a labelled row underneath, because an icon
 * with no label is only legible when you already know what it is.
 *
 * ## Why this is not a DropdownMenu
 *
 * `DropdownMenu` anchors to a composable, and this anchors to a **finger**: the menu should open
 * where the press landed, the way a right-click does. So it is a `Popup` positioned from the
 * press coordinates, with the caller clamping it on screen.
 */
@Composable
fun ContextMenu(
    title: String,
    subtitle: String?,
    quick: List<SelectionAction>,
    rest: List<SelectionAction>,
    onDismiss: () -> Unit,
    onBlocked: (String) -> Unit,
    offsetX: Int,
    offsetY: Int,
) {
    val colors = Filet.colors
    Popup(
        offset = androidx.compose.ui.unit.IntOffset(offsetX, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .widthIn(min = 232.dp, max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.high)
                // A hairline, not a shadow. The palette is warm and nearly flat, and a drop
                // shadow on a dark ground reads as a smudge rather than as elevation.
                .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
                .padding(vertical = 6.dp),
        ) {
            // What this menu is about. On a multi-selection it says how many, which is the
            // answer to the only dangerous question a context menu raises.
            Text(
                title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = colors.fg3,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
                )
            }

            if (quick.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (action in quick) {
                        QuickAction(action, onDismiss, onBlocked)
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = colors.lineSoft)
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                var previous: SelectionAction.Group? = null
                for (action in rest) {
                    if (previous != null && previous != action.group) {
                        HorizontalDivider(
                            color = colors.lineSoft,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    previous = action.group
                    MenuRow(action, onDismiss, onBlocked)
                }
            }
        }
    }
}

/**
 * One of the five across the top.
 *
 * Blocked actions are dimmed and **still tappable**: they answer with the reason instead of
 * acting. A phone has no hover, so a disabled icon with no label and no response tells nobody
 * anything at all - which is the same rule the selection bar follows.
 */
@Composable
private fun QuickAction(action: SelectionAction, onDismiss: () -> Unit, onBlocked: (String) -> Unit) {
    val colors = Filet.colors
    val tint = when {
        action.blocked != null -> colors.fg3.copy(alpha = 0.5f)
        action.danger -> colors.bad
        else -> colors.fg2
    }
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable {
                val why = action.blocked
                if (why != null) onBlocked(why) else action.run()
                onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(action.icon, action.label, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MenuRow(action: SelectionAction, onDismiss: () -> Unit, onBlocked: (String) -> Unit) {
    val colors = Filet.colors
    val tint = when {
        action.blocked != null -> colors.fg3
        action.danger -> colors.bad
        else -> colors.fg2
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val why = action.blocked
                if (why != null) onBlocked(why) else action.run()
                onDismiss()
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(action.label, fontSize = 13.sp, color = tint)
            // The reason lives in the menu rather than in a toast after the tap, so somebody
            // can see why a row is dim without committing to it.
            action.blocked?.let {
                Text(it, fontSize = 9.5.sp, lineHeight = 12.sp, color = colors.fg3)
            }
        }
    }
}

/** The five verbs that go across the top, in Windows' order. */
val QUICK_IDS = listOf("copy", "move", "rename", "send", "delete")

/**
 * Split one action list into the icon row and the rest, preserving order.
 *
 * Built from the same list the selection bar and the selection menu use, so an action can never
 * exist in one surface and not another - that drift is the reason the list is data in the first
 * place.
 */
fun splitForContextMenu(all: List<SelectionAction>): Pair<List<SelectionAction>, List<SelectionAction>> {
    val quick = QUICK_IDS.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val rest = all.filterNot { it.id in QUICK_IDS }
    return quick to rest
}

/** An extra row that is not a selection action - "Select", which starts a multi-selection. */
fun menuAction(
    id: String,
    label: String,
    icon: ImageVector,
    group: SelectionAction.Group = SelectionAction.Group.FILE,
    run: () -> Unit,
): SelectionAction = SelectionAction(id = id, label = label, icon = icon, group = group, run = run)
