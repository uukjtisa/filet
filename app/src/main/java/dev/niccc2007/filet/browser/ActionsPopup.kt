package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The actions popup: everything a selection can do that is not already on the bar.
 *
 * ## Translated from the mock, not from memory
 *
 * `.apop` in `filet-redesign-mock.html`, and the numbers here are its numbers:
 *
 * ```
 * .apop     { width:min(268px,calc(100% - 20px)); right:8px; bottom:56px;
 *             border-radius:14px; padding:6px; border:1px solid var(--line) }
 * .apop .agrp { font-size:10px; text-transform:uppercase; letter-spacing:.1em;
 *               color:var(--fg3); padding:8px 10px 5px; font-weight:600 }
 * .apop .ai { grid-template-columns:20px 1fr auto; gap:10px; padding:8px 10px;
 *             border-radius:9px; font-size:13px }
 * .apop .ai small { font-size:11px; color:var(--fg3); line-height:1.25 }
 * .apop .sep { height:1px; margin:6px 10px;
 *              linear-gradient(90deg,transparent,var(--line) 18%,var(--line) 82%,transparent) }
 * ```
 *
 * The first attempt at this surface was a centred dialogue with blank spacers between the
 * groups and a footer button. It had none of the above: the blank spacers were a section
 * heading with an empty label, which draws a heading's worth of air and no words, so the list
 * read as holes rather than groups. Groups are NAMED here, which is the structure the mock has
 * and the reason the mock has it.
 *
 * ## The one deliberate difference
 *
 * The mock opens with `ctxbarHTML()`, the icon row of quick actions. That row is not repeated
 * here, because the selection bar sitting directly beneath this popup IS that row, and it is
 * now the user's own choice of actions. Drawing it twice, eight dp apart, would be two copies
 * of one control rather than a quick row.
 */
@Composable
fun ActionsPopup(
    actions: List<SelectionAction>,
    onBlocked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors

    // "This file" is what it does to the thing; "Put somewhere" is where it puts it. The two
    // headings are the mock's own, and the app's three groups fold onto them the same way: FILE
    // and EDIT both act on the file, PLACE puts it somewhere.
    val onThis = actions.filter {
        !it.danger && it.group != SelectionAction.Group.PLACE
    }
    val putSomewhere = actions.filter {
        !it.danger && it.group == SelectionAction.Group.PLACE
    }
    // Destructive last and separated - the one instruction the redesign spec gives this surface
    // beyond the grouping.
    val destructive = actions.filter { it.danger }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f))) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // A dismiss target, not a button: a ripple the size of the screen reads as
                    // the app flashing.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val room = maxWidth
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        // right:8px; bottom:56px - it opens above the bar it came from rather
                        // than over it.
                        .padding(end = 8.dp, bottom = 56.dp)
                        .widthIn(max = minOf(268.dp, room - 20.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.raised)
                        .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
                        // Taps inside must not reach the scrim behind it.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(6.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (onThis.isNotEmpty()) {
                        ActionGroupLabel("This file")
                        for (a in onThis) ActionRow(a, onBlocked, onDismiss)
                    }
                    if (putSomewhere.isNotEmpty()) {
                        if (onThis.isNotEmpty()) ActionSeparator()
                        ActionGroupLabel("Put somewhere")
                        for (a in putSomewhere) ActionRow(a, onBlocked, onDismiss)
                    }
                    if (destructive.isNotEmpty()) {
                        ActionSeparator()
                        for (a in destructive) ActionRow(a, onBlocked, onDismiss)
                    }
                }
            }
        }
    }
}

/** `.apop .agrp` */
@Composable
private fun ActionGroupLabel(label: String) {
    Text(
        label.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 5.dp),
    )
}

/**
 * `.apop .sep`
 *
 * A gradient rather than a flat rule, and that is the mock's second pass at it: a full-width
 * hairline inside a 268px card reads as the card being cut in half. It fades out at both ends,
 * so it separates without dividing.
 */
@Composable
private fun ActionSeparator() {
    val line = Filet.colors.lineSoft
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.18f to line,
                    0.82f to line,
                    1f to Color.Transparent,
                ),
            ),
    )
}

/**
 * `.apop .ai` - a 20dp icon column, the label, and anything trailing.
 *
 * A blocked row stays tappable and answers with its reason. A phone has no hover, so a greyed
 * row that does nothing at all is somebody pressing a dead control and guessing why.
 */
@Composable
private fun ActionRow(
    action: SelectionAction,
    onBlocked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    val ink = when {
        action.blocked != null -> colors.fg3
        action.danger -> colors.bad
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable {
                val why = action.blocked
                if (why != null) onBlocked(why) else action.run()
                onDismiss()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                action.icon,
                null,
                tint = if (action.danger) colors.bad else colors.fg2,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                action.label,
                fontSize = 13.sp,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // `.ai small` - the reason, in the row, rather than only in a toast after the tap.
            action.blocked?.let {
                Spacer(Modifier.height(1.dp))
                Text(it, fontSize = 11.sp, lineHeight = 14.sp, color = colors.fg3)
            }
        }
    }
}
