package dev.niccc2007.filet.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.delay

/**
 * The dock: one round button, bottom right of every folder pane.
 *
 * ## What it is for, and why it is not a bar
 *
 * Two faults, one control. **Making something new** was only in the overflow menu, three taps
 * from the thing you were looking at. And **the clipboard was invisible**: after a Copy the
 * app held state that nothing on screen mentioned, so a clipboard you cannot see is a
 * clipboard you forget you filled.
 *
 * A fifth draggable band along the bottom was the obvious answer and it is the wrong one. That
 * edge already carries the navigation bar, the now-playing strip and the selection bar; a band
 * would cover the list permanently and would have to collide-detect against three other
 * things. A round button in a corner gets out of the way by being small.
 *
 * ## The badge is the point
 *
 * With nothing copied the dock is a `+`. With something on the clipboard it grows a count you
 * cannot walk past, and the primary button becomes **Paste**, because at that moment pasting
 * is what you came to do. That is the resolution of "I don't understand the paste pill — it
 * removed the dock; I wanted a universal clipboard": the pill is not a separate surface
 * competing with the dock, it IS the dock's clipboard in its loaded state.
 *
 * The fan-out stagger is 30 ms a leaf, from the bottom up, as in the mock - it reads as the
 * stack unfolding rather than as four things appearing at once.
 */
@Composable
fun Dock(
    pane: PaneController,
    vm: BrowserViewModel,
    s: PaneState,
    app: AppState,
    modifier: Modifier = Modifier,
) {
    if (s.kind != PaneKind.FOLDER || s.cwd == null) return
    // Out of the way while a selection is running: the selection bar owns the bottom of the
    // screen then, and its own Copy/Move/Delete are the actions in hand.
    if (s.selecting) return

    val colors = Filet.colors
    var open by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    val loaded = app.clips.totalItems

    // Closing on a change of folder: the dock is per-pane, and a fan left open across a
    // navigation is a menu hanging over a screen it was not opened on.
    LaunchedEffect(s.cwd) { open = false }

    Box(modifier.fillMaxSize()) {
        // The scrim. Only while the fan is out, and it closes it - a fanned dock that stays
        // out while you tap a file is four buttons over the thing you were trying to reach.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = false },
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top to bottom in the layout, so the delays run bottom-up: the leaf nearest the
            // button moves first, which is what makes it read as unfolding FROM the button.
            DockLeaf("New archive", FiletIcons.Archive, open, delay = 90) {
                open = false; vm.askCompress()
            }
            DockLeaf("New file", FiletIcons.File, open, delay = 60) {
                open = false; vm.askNewFile()
            }
            DockLeaf("New folder", FiletIcons.NewFolder, open, delay = 30) {
                open = false; vm.askNewFolder()
            }
            if (loaded > 0) {
                DockLeaf(
                    "Clipboard  ·  $loaded",
                    FiletIcons.Paste,
                    open,
                    delay = 0,
                    badge = app.clips.size,
                ) { open = false; tray = true }
            }

            // The loaded state. A pill above the `+`, not instead of it: making a new folder
            // must not become unreachable because something is on the clipboard.
            if (loaded > 0) {
                PastePill(
                    count = app.clips.totalPicked,
                    blocked = vm.writeBlockReason(s.cwd),
                    onPaste = { vm.pasteInto(pane) },
                    onOpenTray = { tray = true },
                )
            }

            DockButton(
                icon = FiletIcons.Plus,
                label = if (open) "Close" else "New",
                ground = colors.accent,
                ink = MaterialTheme.colorScheme.onPrimary,
                size = 50.dp,
                rotated = open,
            ) { open = !open }
        }

        if (tray) {
            ClipTraySheet(
                pane = pane,
                vm = vm,
                dest = s.cwd,
                tray = app.clips,
                onDismiss = { tray = false },
            )
        }
    }
}

/**
 * The paste control, in the dock's own stack.
 *
 * Counts what is TICKED rather than what is held, because that is what pressing it will do -
 * a button saying 11 that moves 4 is a button that lied once and will not be trusted again.
 * The chevron opens the tray, which is where the difference between those two numbers is.
 */
@Composable
private fun PastePill(
    count: Int,
    blocked: String?,
    onPaste: () -> Unit,
    onOpenTray: () -> Unit,
) {
    val colors = Filet.colors
    val live = blocked == null && count > 0
    Row(
        Modifier
            .shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(if (live) colors.accent else colors.high),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                // Refuses with a reason on a read-only volume rather than disappearing. A
                // control that vanishes leaves you wondering whether the copy survived.
                .clickable { if (live) onPaste() else onOpenTray() }
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FiletIcons.Paste, null,
                tint = if (live) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                when {
                    blocked != null -> "Read-only here"
                    count == 0 -> "Nothing ticked"
                    else -> "Paste $count"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (live) MaterialTheme.colorScheme.onPrimary else colors.fg2,
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenTray),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FiletIcons.Up, "Open the clipboard",
                tint = if (live) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** One fanned-out leaf: a label beside a small round button. */
@Composable
private fun DockLeaf(
    label: String,
    icon: ImageVector,
    open: Boolean,
    delay: Int,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    // The stagger is a per-leaf delay on one shared target, which is how the mock does it
    // (`transition-delay:.00/.03/.06/.09s`). Closing runs with no delay at all: an unfolding
    // that takes 120 ms to begin feels alive, a closing one feels stuck.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) { delay(delay.toLong()); armed = true } else armed = false
    }
    val t by animateFloatAsState(
        if (armed) 1f else 0f,
        animationSpec = tween(if (armed) 180 else 120),
        label = "leaf",
    )
    if (t <= 0.001f) return
    Row(
        Modifier
            .graphicsLayer {
                alpha = t
                translationY = (1f - t) * 18f
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Box {
            DockButton(
                icon = icon,
                label = label,
                ground = colors.high,
                ink = colors.fg2,
                size = 42.dp,
                onClick = onClick,
            )
            if (badge > 0) {
                Badge(badge, Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
            }
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    ground: Color,
    ink: Color,
    size: androidx.compose.ui.unit.Dp,
    rotated: Boolean = false,
    onClick: () -> Unit,
) {
    val spin by animateFloatAsState(
        if (rotated) 45f else 0f,
        animationSpec = tween(180),
        label = "dock spin",
    )
    Box(
        Modifier
            .shadow(5.dp, CircleShape)
            .size(size)
            .clip(CircleShape)
            .background(ground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = ink,
            modifier = Modifier.size(size * 0.42f).graphicsLayer { rotationZ = spin },
        )
    }
}

@Composable
private fun Badge(count: Int, modifier: Modifier = Modifier) {
    val colors = Filet.colors
    Box(
        modifier
            .size(17.dp)
            .clip(CircleShape)
            .background(colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 9) "9+" else "$count",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
