package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgAction
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import kotlin.math.roundToInt

/**
 * Submenus that open beside the menu they came from, the way a context submenu does on Windows.
 *
 * A row with [SelectionAction.children] opens one. It sits beside its menu, level with the row,
 * overlapping the menu's edge a little so the two read as one: right of the menu where there is
 * room, left where there is not, and over the panels behind it when there is room on neither
 * side, never off the screen at any depth (see [MenuPlace.land]). It moves up rather than run off
 * the bottom, and scrolls when it is taller than the screen. A row inside a submenu can open
 * another by the same rules.
 *
 * Closing: the submenu's own back row, Back (newest first, then the menu), or the row that opened
 * it. A tap outside every menu closes them all.
 *
 * Everything is drawn in one full-window layer, so every menu and every row is measured in the
 * same coordinates and placing one beside another is arithmetic rather than a guess about where
 * another window ended up.
 */
@Stable
internal class SubMenus {
    /** One open submenu: what opened it, and that row's bounds in the layer. */
    class Level(val action: SelectionAction, val row: Rect)

    /** `levels[i]` was opened from a row of panel `i`; panel 0 is the menu itself. */
    val levels = mutableStateListOf<Level>()

    /** Where each panel is in the layer, by panel number. */
    val panels = mutableStateMapOf<Int, Rect>()

    fun isOpen(level: Int, id: String): Boolean = levels.getOrNull(level)?.action?.id == id

    /** Open [action]'s submenu from panel [level], or close it if it is the one open there. */
    fun toggle(level: Int, action: SelectionAction, row: Rect) {
        val was = isOpen(level, action.id)
        close(level)
        if (!was) levels.add(Level(action, row))
    }

    /** Close every submenu opened from panel [level] or above it. */
    fun close(level: Int) {
        while (levels.size > level) levels.removeAt(levels.lastIndex)
        panels.keys.filter { it > level }.forEach { panels.remove(it) }
    }

    /** Close the newest submenu; false when there is none, so Back closes the menu instead. */
    fun back(): Boolean {
        if (levels.isEmpty()) return false
        close(levels.lastIndex)
        return true
    }
}

@Composable
internal fun rememberSubMenus(): SubMenus = remember { SubMenus() }

/** The part of the layer a menu may use: the screen less the system bars and a margin. */
internal data class MenuRoom(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Where menus go. Pure, so the edge rules are tested without a screen. */
internal object MenuPlace {

    /** A menu opened at a press: at the finger, pushed in from any edge it would cross. */
    fun atPress(x: Int, y: Int, width: Int, height: Int, room: MenuRoom): IntOffset = IntOffset(
        x.coerceIn(room.left, maxOf(room.left, room.right - width)),
        y.coerceIn(room.top, maxOf(room.top, room.bottom - height)),
    )

    /**
     * The rect a submenu lands in: from its parent panel, the top of the row that opened it, its
     * own size and the room. Never off the screen, at any depth.
     *
     * Beside the parent when it fits, right first and then left, reaching over the parent's edge
     * by [overlap]. With room on neither side, as on a phone, it covers part of the panels behind
     * it, from whichever screen edge leaves more of its parent showing. So a third level lands
     * across from the second rather than on top of it, and every level keeps a strip in view.
     * Level with its row, moved up rather than past the bottom, and never taller or wider than
     * the room: past that it scrolls.
     */
    fun land(parent: IntRect, rowTop: Int, size: IntSize, room: MenuRoom, overlap: Int): IntRect {
        val w = minOf(size.width, room.width)
        val h = minOf(size.height, room.height)
        val right = parent.right - overlap
        val left = parent.left - w + overlap
        val x = when {
            right + w <= room.right -> right
            left >= room.left -> left
            else -> {
                val atRight = room.right - w
                // What of the parent stays in view either way: its strip left of a submenu at the
                // right edge, or right of one at the left edge.
                val showsIfRight = atRight - parent.left
                val showsIfLeft = parent.right - (room.left + w)
                if (showsIfLeft > showsIfRight) room.left else atRight
            }
        }
        val y = rowTop.coerceAtMost(room.bottom - h).coerceAtLeast(room.top)
        return IntRect(x, y, x + w, y + h)
    }
}

/** How far a submenu reaches over the edge of the menu it came from. */
private val OVERLAP = 4.dp

/** Between any menu and the edge of the room. */
private val MENU_MARGIN = 8.dp

/** [MenuRoom] for a layer of [width] by [height] pixels drawn under the system bars. */
@Composable
internal fun menuRoom(width: Int, height: Int): MenuRoom {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bars = WindowInsets.safeDrawing
    val m = with(density) { MENU_MARGIN.roundToPx() }
    return MenuRoom(
        left = bars.getLeft(density, dir) + m,
        top = bars.getTop(density) + m,
        right = width - bars.getRight(density, dir) - m,
        bottom = height - bars.getBottom(density) - m,
    )
}

/** Taps on a panel's own background stop there instead of reaching the layer, which closes all. */
@Composable
internal fun Modifier.keepTaps(): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = {},
)

/**
 * A full-window layer for a menu opened at a press, with its submenus.
 *
 * A dialogue window rather than a popup, because a popup is sized to its content and a submenu
 * has to be able to sit anywhere on the screen. Nothing is dimmed: it is still a menu.
 */
@Composable
internal fun MenuLayer(
    subs: SubMenus,
    onDismiss: () -> Unit,
    onBlocked: (String) -> Unit,
    content: @Composable BoxScope.(room: MenuRoom, onScreen: IntOffset) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!subs.back()) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        // Where this window starts on the screen, so a press measured in the app's window lands
        // in the same place here. Usually the corner of the screen, but not where a window has
        // been kept clear of a cutout or the app shares the screen.
        var onScreen by remember { mutableStateOf(IntOffset.Zero) }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val at = IntArray(2).also(view::getLocationOnScreen)
                    onScreen = IntOffset(at[0], at[1])
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            val room = menuRoom(constraints.maxWidth, constraints.maxHeight)
            content(room, onScreen)
            SubMenuStack(subs, onBlocked, onDismiss)
        }
    }
}

/** Place the menu at a press inside [room], the press in this layer's own coordinates. */
internal fun Modifier.atPress(x: Int, y: Int, room: MenuRoom): Modifier = layout { m, c ->
    val p = m.measure(Constraints(maxWidth = room.width.coerceAtLeast(0), maxHeight = room.height.coerceAtLeast(0)))
    layout(c.maxWidth, c.maxHeight) { p.place(MenuPlace.atPress(x, y, p.width, p.height, room)) }
}

/**
 * Every open submenu, each beside the panel it came from. Goes last in a full-window box at the
 * window's origin, so its coordinates are the rows' coordinates.
 */
@Composable
internal fun SubMenuStack(subs: SubMenus, onBlocked: (String) -> Unit, onDone: () -> Unit) {
    if (subs.levels.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val room = menuRoom(constraints.maxWidth, constraints.maxHeight)
        subs.levels.toList().forEachIndexed { i, level ->
            val parent = subs.panels[i] ?: return@forEachIndexed
            key(i, level.action.id) {
                SubMenuPanel(
                    panel = i + 1,
                    action = level.action,
                    subs = subs,
                    onBlocked = onBlocked,
                    onDone = onDone,
                    modifier = Modifier
                        .layout { m, c ->
                            val p = m.measure(
                                Constraints(maxWidth = room.width.coerceAtLeast(0), maxHeight = room.height.coerceAtLeast(0)),
                            )
                            val at = MenuPlace.land(
                                parent = IntRect(
                                    parent.left.roundToInt(), parent.top.roundToInt(),
                                    parent.right.roundToInt(), parent.bottom.roundToInt(),
                                ),
                                rowTop = level.row.top.roundToInt(),
                                size = IntSize(p.width, p.height),
                                room = room,
                                overlap = OVERLAP.roundToPx(),
                            ).topLeft
                            layout(c.maxWidth, c.maxHeight) { p.place(at) }
                        }
                        .onGloballyPositioned { subs.panels[i + 1] = it.boundsInRoot() },
                )
            }
        }
    }
}

/** One submenu: a back row named for what opened it, then its rows under their headings. */
@Composable
private fun SubMenuPanel(
    panel: Int,
    action: SelectionAction,
    subs: SubMenus,
    onBlocked: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .clip(shape)
            .background(colors.high)
            .border(1.dp, colors.lineSoft, shape)
            .keepTaps()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Close") { subs.close(panel - 1) }
                .padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(FiletIcons.Back, "Back", tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                action.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
        var heading: String? = null
        for (child in action.children) {
            val section = child.section
            if (section != null && section != heading) MenuHeading(section)
            heading = section
            MenuEntry(child, level = panel, subs = subs, onBlocked = onBlocked, onDone = onDone)
        }
    }
}

/** A submenu's heading: the group its next rows belong to. */
@Composable
private fun MenuHeading(label: String) {
    Text(
        label.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 3.dp),
    )
}

/**
 * A row of a menu or submenu. One with children opens or closes its submenu and is lit while
 * it is open, so it is clear what the submenu belongs to while it covers part of the menu.
 * A blocked row answers with its reason, as every menu row does.
 */
@Composable
internal fun MenuEntry(
    action: SelectionAction,
    level: Int,
    subs: SubMenus,
    onBlocked: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = Filet.colors
    var row by remember { mutableStateOf(Rect.Zero) }
    val open = subs.isOpen(level, action.id)
    val tint = when {
        action.blocked != null -> colors.fg3
        action.danger -> colors.bad
        open -> MaterialTheme.colorScheme.onSurface
        else -> colors.fg2
    }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { row = it.boundsInRoot() }
            .background(if (open) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { pressed(action, level, row, subs, onBlocked, onDone) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(action.icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(action.label, fontSize = 13.sp, color = tint)
            // The reason lives in the menu rather than in a toast after the tap, so somebody can
            // see why a row is dim without committing to it.
            val under = action.blocked ?: action.detail
            if (under != null) Text(under, fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.fg3)
        }
        if (action.children.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                FiletIcons.Chevron,
                if (open) "Close ${action.label}" else "Open ${action.label}",
                tint = if (open) colors.accent else colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** What pressing a menu row does, wherever the row is drawn. */
internal fun pressed(
    action: SelectionAction,
    level: Int,
    row: Rect,
    subs: SubMenus,
    onBlocked: (String) -> Unit,
    onDone: () -> Unit,
) {
    val why = action.blocked
    when {
        why != null -> {
            onBlocked(why)
            onDone()
        }
        action.children.isNotEmpty() -> subs.toggle(level, action, row)
        else -> {
            action.run()
            onDone()
        }
    }
}

/**
 * A row's submenu as Filet's standard dialogue, for a bar or a panel with no menu to sit beside:
 * the same rows under the same headings.
 */
@Composable
internal fun ActionChoiceDialog(
    action: SelectionAction,
    sub: String?,
    onBlocked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(action.icon, action.label, sub = sub, onClose = onDismiss)
        DlgBody {
            var heading: String? = null
            for (child in action.children) {
                val section = child.section
                if (section != null && section != heading) DlgSection(section)
                heading = section
                DlgAction(child.icon, child.label, sub = child.blocked ?: child.detail, danger = child.danger) {
                    onDismiss()
                    val why = child.blocked
                    if (why != null) onBlocked(why) else child.run()
                }
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", BtnKind.PLAIN, fill = false, onClick = onDismiss)
        }
    }
}

/** The Share dialogue's line under its title. */
internal const val SHARE_SUB = "Filet's own ways. Send hands files to another app."
