package dev.niccc2007.filet.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.TransformOrigin
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
 * ## The clipboard rides on top of it
 *
 * With nothing copied the dock is a `+`. With something new on the clipboard a **Paste** pill
 * sits above it, because at that moment pasting is what you came to do. The pill is not a
 * separate surface competing with the dock; it is the dock's clipboard in its loaded state, and
 * an earlier version that replaced the dock with it made New folder unreachable.
 *
 * Once everything new has been pasted the pill shrinks to a 42dp circle - the size of a dock
 * leaf, so it reads as part of the stack - holding the history. Bug identified here: the pill
 * stayed full width after every copy had been used, a button over the list offering to repeat
 * what had just been done, and vanished after a move with no record that the move happened.
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
    /** Pushed up by this much, to clear the pick card when one is up. See `PickDock`. */
    liftBy: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (s.kind != PaneKind.FOLDER || s.cwd == null) return

    // Where a paste or a new folder actually lands. `target`, not `cwd`: with tree view on,
    // `cwd` stays at the volume root and only `active` moves as branches open, so `cwd` is not
    // "where the user is" and reading it put everything at the top of the storage. See
    // `PaneState.target`.
    val here = s.target ?: s.cwd ?: return
    // Out of the way while a selection is running: the selection bar owns the bottom of the
    // screen then, and its own Copy/Move/Delete are the actions in hand.
    //
    // Except during a pick, which is the one time the opposite is true. Choosing a destination
    // is exactly when somebody needs a NEW FOLDER to put things in, and a pick for files leaves
    // rows selected - so this rule was quietly removing the dock for the whole of every
    // cover-art and multi-file pick, and for any pick where a row happened to be selected.
    // There is no selection bar to clash with during a pick either: the card carries the
    // confirm. See `PickDock`.
    val picking = vm.inAppPick.collectAsState().value?.pane === pane || vm.pickRequest != null
    if (s.selecting && !picking) return

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
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp + liftBy),
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

            // The loaded state. Above the `+`, not instead of it: making a new folder must not
            // become unreachable because something is on the clipboard. The pill while anything
            // is new, the circle once all of it has been used - or is being pasted, since the job
            // is in Activity then and a second Paste over the list would only start it again.
            val fresh = app.clips.waiting
            if (fresh.isNotEmpty()) {
                PastePill(
                    count = app.clips.waitingPicked,
                    move = app.clips.waitingAllMoves,
                    blocked = vm.writeBlockReason(here),
                    onPaste = { vm.pasteInto(pane) },
                    onOpenTray = { tray = true },
                )
            } else if (loaded > 0) {
                ClipDot(held = app.clips.history.size) { tray = true }
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
                dest = here,
                tray = app.clips,
                onDismiss = { tray = false },
            )
        }
    }
}

/** cubic-bezier(.2,.8,.2,1), the mock's arrival curve for both the pill and the circle. */
private val Arrive = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * The paste control, in the dock's own stack. New things only; see [BrowserViewModel.pasteInto].
 *
 * Counts what is TICKED rather than what is held, because that is what pressing it will do -
 * a button saying 11 that moves 4 is a button that lied once and will not be trusted again.
 * The chevron opens the clipboard, which is where the difference between those two numbers is.
 * Says Move when every new batch is a move, because Paste over a move is a promise about where
 * the originals will be that it does not keep.
 *
 * Arrives in 200 ms, growing leftwards from its right edge (the mock's `pillIn`).
 */
@Composable
private fun PastePill(
    count: Int,
    move: Boolean,
    blocked: String?,
    onPaste: () -> Unit,
    onOpenTray: () -> Unit,
) {
    val colors = Filet.colors
    val live = blocked == null && count > 0
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(200, easing = Arrive)) }
    Row(
        Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(1f, 0.5f)
                scaleX = 0.4f + 0.6f * shown.value
                alpha = shown.value
            }
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
                    move -> "Move $count"
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

/**
 * The clipboard once everything new has been used: a 42dp circle holding the history, with the
 * count of history rows on a badge (9+ past nine, because a two-digit number does not fit a 17dp
 * badge legibly). Tap opens the clipboard.
 *
 * Scales in from 0.6 over 220 ms (the mock's `shrinkIn`), which is what makes it read as the
 * pill shrinking rather than one control vanishing and another appearing.
 */
@Composable
private fun ClipDot(held: Int, onOpen: () -> Unit) {
    val colors = Filet.colors
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(220, easing = Arrive)) }
    Box(
        Modifier
            .padding(end = 4.dp)
            .graphicsLayer {
                val s = 0.6f + 0.4f * shown.value
                scaleX = s
                scaleY = s
                alpha = shown.value
            },
    ) {
        Box(
            Modifier
                .size(42.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.high)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FiletIcons.Paste, "Clipboard", tint = colors.fg2, modifier = Modifier.size(18.dp))
        }
        if (held > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (held > 9) "9+" else "$held",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
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
