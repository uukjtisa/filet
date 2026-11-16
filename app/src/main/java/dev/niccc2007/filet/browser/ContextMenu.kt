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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import dev.niccc2007.filet.ui.dialogs.DlgCtxHead
import dev.niccc2007.filet.ui.dialogs.DlgIconBar
import dev.niccc2007.filet.ui.dialogs.DlgIconBtn
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The right-click menu, as a phone gesture.
 *

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
 * where the press landed, the way a right-click does. So it is placed from the press
 * coordinates, in a full-window layer (see SubMenu.kt) that pushes it in from any edge it would
 * cross and lets a submenu open beside it.
 */
@Composable
fun ContextMenu(
    title: String,
    subtitle: String?,
    /** One line of facts under the path - the size and the date, or how many are selected. */
    meta: String? = null,
    /** The file this menu is about, for the thumbnail. Null for a multi-selection. */
    thumb: dev.niccc2007.filet.vfs.VNode? = null,
    quick: List<SelectionAction>,
    rest: List<SelectionAction>,
    onDismiss: () -> Unit,
    onBlocked: (String) -> Unit,
    offsetX: Int,
    offsetY: Int,
) {
    val colors = Filet.colors
    val subs = rememberSubMenus()
    // The press is measured in the app's window; the layer is a window of its own.
    val host = androidx.compose.ui.platform.LocalView.current
    val hostOnScreen = remember(host) { IntArray(2).also(host::getLocationOnScreen) }
    MenuLayer(subs = subs, onDismiss = onDismiss, onBlocked = onBlocked) { room, layerOnScreen ->
        Column(
            Modifier
                // At the press, pushed in from any edge it would cross. The layer is the whole
                // window, so a submenu can sit beside this wherever it is.
                .atPress(
                    offsetX + hostOnScreen[0] - layerOnScreen.x,
                    offsetY + hostOnScreen[1] - layerOnScreen.y,
                    room,
                )
                .onGloballyPositioned { subs.panels[0] = it.boundsInRoot() }
                // Wider than it was. The header now carries a path, and 300dp ellipsised a
                // path down to almost nothing on the screen where the path matters most.
                .widthIn(min = 248.dp, max = 330.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.high)
                // A hairline, not a shadow. The palette is warm and nearly flat, and a drop
                // shadow on a dark ground reads as a smudge rather than as elevation.
                .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
                .keepTaps()
                .padding(bottom = 6.dp),
        ) {
            // The name, the path, and how many when it is a selection. DlgCtxHead, so this
            // menu's header is the same object as every other dialogue's - the reason the
            // path is in it rather than the file kind is written there.
            // `thumb` is null on a multi-selection: there is no one file for the slot to show,
            // and a placeholder for "several" would be a picture of nothing.
            DlgCtxHead(name = title, path = subtitle ?: "", meta = meta, thumb = thumb)

            if (quick.isNotEmpty()) {
                DlgIconBar {
                    for (action in quick) {
                        var at by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                        // A row of its own, for the button's weight and to know where it is.
                        Row(Modifier.weight(1f).onGloballyPositioned { at = it.boundsInRoot() }) {
                            DlgIconBtn(
                                icon = action.icon,
                                label = action.label,
                                // Blocked stays TAPPABLE and answers with the reason. A phone has
                                // no hover, so a dimmed glyph that also ignores the tap tells
                                // nobody anything - the same rule the selection bar follows.
                                enabled = true,
                                danger = action.danger,
                            ) { pressed(action, 0, at, subs, onBlocked, onDismiss) }
                        }
                    }
                }
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
                    MenuEntry(action, level = 0, subs = subs, onBlocked = onBlocked, onDone = onDismiss)
                }
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
fun splitForContextMenu(
    all: List<SelectionAction>,
    /** Which ids go across the top, in order. See [SelectionBarConfig.contextQuick]. */
    quickIds: List<String> = QUICK_IDS,
): Pair<List<SelectionAction>, List<SelectionAction>> {
    val quick = quickIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val taken = quick.map { it.id }.toSet()
    val rest = all.filterNot { it.id in taken }
    return quick to rest
}

/**
 * An extra row that is not a selection action.
 *
 * Was introduced for "Select" and for "Copy path". Select is gone - see the note at its old
 * call site - so Copy path is the only user left, and the helper stays because a second one
 * will turn up and building it as a one-off is how the list stopped being the single source.
 */
fun menuAction(
    id: String,
    label: String,
    icon: ImageVector,
    group: SelectionAction.Group = SelectionAction.Group.FILE,
    run: () -> Unit,
): SelectionAction = SelectionAction(id = id, label = label, icon = icon, group = group, run = run)
