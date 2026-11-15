package dev.niccc2007.filet.ui.dialogs

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
 * The panel a small button opens: a rounded card beside the button, a small-capitals heading,
 * and rows that are either actions or switches.
 *
 * First drawn for the mini player's Background glow, and shared from here so every panel of this
 * kind is the same card: the glow's, and each viewer's More. A menu that answers with a list of
 * commands is [PopMenu]; this is the one that also shows state, a switch per setting, and keeps
 * the thing it is about in view while it is open.
 *
 * Right-aligned to its button and on the [side] asked for, unless that side has no room and the
 * other has. A list taller than the screen allows scrolls inside the card.
 */
@Composable
fun PanelPopover(
    side: PanelSide,
    onDismiss: () -> Unit,
    heading: String? = null,
    gap: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A viewer keeps its controls up while this is open, as it does for a dialogue.
    OverlayHold.Hold()
    val colors = Filet.colors
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    val place = remember(side, gapPx) { PanelPlace(side, gapPx) }
    Popup(
        popupPositionProvider = place,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(PANEL_WIDTH)
                .heightIn(max = tallest)
                .shadow(22.dp, PANEL_SHAPE)
                .clip(PANEL_SHAPE)
                .background(colors.raised)
                .border(1.dp, colors.lineSoft, PANEL_SHAPE)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 14.dp),
        ) {
            heading?.let { PanelHeading(it) }
            content()
        }
    }
}

/** Which side of its button a panel opens on: above for a bottom bar, below for a top one. */
enum class PanelSide { ABOVE, BELOW }

/** A panel's heading, in small capitals. A second one starts a second section. */
@Composable
fun PanelHeading(text: String, first: Boolean = true) {
    Text(
        text.uppercase(),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        color = Filet.colors.fg3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = if (first) 0.dp else 4.dp, bottom = 11.dp),
    )
}

/** A hairline that stops short of the panel's edge, so the corners stay round. */
@Composable
fun PanelRule() {
    Box(
        Modifier
            .padding(horizontal = 17.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Filet.colors.lineSoft),
    )
}

/**
 * One row. [on] null makes it an action rather than a switch.
 *
 * @param icon drawn before the label, for a row that is also somewhere else with that icon.
 * @param ink the label's colour when it is not a switch that is on: the warning colour for an
 *   action that cannot be undone.
 * @param dim a row present but unable to run right now; it is still pressed, to say why.
 */
@Composable
fun PanelRow(
    label: String,
    tint: Color,
    on: Boolean?,
    icon: ImageVector? = null,
    ink: Color? = null,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val text = when {
        on == true -> tint
        ink != null -> ink
        else -> colors.fg2
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (dim) 0.45f else 1f)
            .padding(horizontal = 17.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink ?: colors.fg3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(11.dp))
        }
        Text(
            label,
            fontSize = 12.5.sp,
            color = text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (on != null) PanelSwitch(on, tint)
    }
}

/** The smallest honest switch: a track, and a knob at one end of it. */
@Composable
fun PanelSwitch(on: Boolean, tint: Color) {
    val colors = Filet.colors
    val slide by animateDpAsState(if (on) 17.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .size(width = 34.dp, height = 19.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) tint.copy(alpha = 0.3f) else colors.sunken),
    ) {
        Box(
            Modifier
                .padding(start = slide)
                .align(Alignment.CenterStart)
                .size(15.dp)
                .clip(CircleShape)
                .background(if (on) tint else colors.fg3),
        )
    }
}

private val PANEL_WIDTH = 238.dp
private val PANEL_SHAPE = RoundedCornerShape(22.dp)

/** Right-aligned to the button, on the side asked for when it fits there. */
private class PanelPlace(private val side: PanelSide, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val room = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, room)
        val above = anchorBounds.top - popupContentSize.height - gap
        val below = anchorBounds.bottom + gap
        val fitsAbove = above >= 0
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        val y = when (side) {
            PanelSide.ABOVE -> if (fitsAbove || !fitsBelow) above else below
            PanelSide.BELOW -> if (fitsBelow || !fitsAbove) below else above
        }
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}
