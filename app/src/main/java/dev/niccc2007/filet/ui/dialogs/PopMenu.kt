package dev.niccc2007.filet.ui.dialogs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.niccc2007.filet.ui.theme.Filet

/**
 * A menu anchored to the button that opened it, in the look of the app's actions popup (`.apop`
 * in the redesign mock): a 14dp card on the raised colour, named groups, rows of a 20dp icon
 * column, the label and an optional small line under it, and the destructive row last.
 *
 * What a toolbar's overflow uses instead of Material's `DropdownMenu`, which drew in the
 * platform's own shape and colours and was the one menu in the app that looked like another
 * app's. A `Popup` rather than a dialogue: it stays attached to the button, and over a video it
 * does not take the window's focus in a way that brings the system bars back.
 *
 * Place it inside the same `Box` as its button; it opens under the button's end edge, or over
 * it for a button on a bottom bar. Where that would leave the screen it opens the other way and
 * is held inside the edges - see [PopPlace].
 *
 * @param below how far from the anchor's edge the card starts - the button's height.
 * @param above open upwards, for a button along the bottom of the screen.
 * @param centred centre the card on the button rather than lining up their end edges - a button
 *   in the middle of a bar, where an end-aligned card would run off the other side.
 */
@Composable
fun PopMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    below: Dp = 36.dp,
    above: Boolean = false,
    centred: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    OverlayHold.Hold()
    val colors = Filet.colors
    val density = LocalDensity.current
    val gap = with(density) { below.roundToPx() }
    val side = with(density) { PopPlace.SIDE.roundToPx() }
    val edge = with(density) { PopPlace.EDGE.roundToPx() }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(140)) }
    Popup(
        popupPositionProvider = remember(gap, above, centred, side, edge) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = PopPlace.of(
                    anchorBounds, windowSize, popupContentSize, gap,
                    above = above, centred = centred, ltr = layoutDirection == LayoutDirection.Ltr,
                    side = side, edge = edge,
                )
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(end = if (centred) 0.dp else 4.dp)
                .widthIn(min = 200.dp, max = 280.dp)
                .graphicsLayer {
                    alpha = shown.value
                    val s = 0.94f + 0.06f * shown.value
                    scaleX = s
                    scaleY = s
                    // Grows out of the button it came from.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        if (centred) 0.5f else 1f,
                        if (above) 1f else 0f,
                    )
                }
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(colors.raised)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
                .padding(6.dp),
            content = content,
        )
    }
}

/**
 * Where a [PopMenu] card goes, in window pixels. Pure.
 *
 * Decision: placed by this rather than by a `Popup` alignment, because the card now opens from
 * the rows of a list as well as from the buttons of a bar. An alignment cannot flip: a card that
 * opens under a row near the bottom of a list runs off the screen, and one end-aligned to
 * something near the start edge runs off the side. Material's dropdown flipped and clamped, and
 * moving the row menus onto this card would otherwise have traded its look for a menu that
 * cannot be read.
 *
 * The preferred side is [above] or below; the other side is taken only when the preferred one
 * does not fit and the other does. Whichever side, the card is then held [side] from the left
 * and right of the window and [edge] from the top and bottom, which keeps it clear of the status
 * and navigation bars on an edge-to-edge window. A card too big for the window keeps its top
 * and start edge on screen, where its first rows are.
 */
internal object PopPlace {

    val SIDE = 8.dp
    val EDGE = 24.dp

    fun of(
        anchor: IntRect,
        window: IntSize,
        card: IntSize,
        gap: Int,
        above: Boolean,
        centred: Boolean,
        ltr: Boolean,
        side: Int,
        edge: Int,
    ): IntOffset {
        val x = when {
            centred -> anchor.left + (anchor.width - card.width) / 2
            ltr -> anchor.right - card.width
            else -> anchor.left
        }
        // Under: the card's top `gap` below the anchor's top - a button's height puts it just
        // under the button. Over: the mirror, its bottom `gap` above the anchor's bottom.
        val under = anchor.top + gap
        val over = anchor.bottom - gap - card.height
        val fitsUnder = under + card.height <= window.height - edge
        val fitsOver = over >= edge
        val y = if (above) {
            if (fitsOver || !fitsUnder) over else under
        } else {
            if (fitsUnder || !fitsOver) under else over
        }
        return IntOffset(
            x.coerceAtMost(window.width - card.width - side).coerceAtLeast(side),
            y.coerceAtMost(window.height - card.height - edge).coerceAtLeast(edge),
        )
    }
}

/** `.apop .agrp` - a group's name, small and spaced. */
@Composable
fun PopGroup(label: String) {
    Text(
        label.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 5.dp),
    )
}

/** `.apop .sep` - faded at both ends, so it separates without cutting the card in half. */
@Composable
fun PopSeparator() {
    val line = Filet.colors.lineSoft
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(1.dp)
            .background(Brush.horizontalGradient(0f to Color.Transparent, 0.18f to line, 0.82f to line, 1f to Color.Transparent)),
    )
}

/**
 * `.apop .ai` - one row.
 *
 * @param detail one line under the label saying what the row does, for the row whose name
 *   alone does not - "Play as music" is the case it exists for.
 * @param active a mode that is on now, drawn lit.
 * @param checked a switch, with a tick at the end while it is on and the tick's room kept while
 *   it is off, so the row reads as something that can be ticked. Null for an action.
 */
@Composable
fun PopRow(
    icon: ImageVector,
    label: String,
    detail: String? = null,
    danger: Boolean = false,
    active: Boolean = false,
    checked: Boolean? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ink = when {
        danger -> colors.bad
        active -> colors.accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (danger) colors.bad else if (active) colors.accent else colors.fg2, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Spacer(Modifier.height(1.dp))
                Text(detail, fontSize = 11.sp, lineHeight = 14.sp, color = colors.fg3)
            }
        }
        if (checked != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                if (checked) {
                    Icon(dev.niccc2007.filet.browser.FiletIcons.Check, "On", tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
