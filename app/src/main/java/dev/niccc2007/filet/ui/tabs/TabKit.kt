package dev.niccc2007.filet.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet
import java.util.Locale

/**
 * The pieces every redesigned tab is built from.
 *
 * These exist because nine screens were each drawing their own heading, their own row and
 * their own toggle, and they had drifted apart - the same control was 11sp on one screen and
 * 12.5sp on the next, and two of them had quietly lost a control altogether. One definition
 * per thing means a change lands everywhere and a missing control is visible as a missing
 * call.
 *
 * Sizes, radii and weights come from the UI mock's CSS rather than being chosen here, so the
 * mock stays a specification instead of decoration. Where a name below matches a class name
 * there, it is the same thing: [TabHeader] is `.thead`, [TRow] is `.trow2`, [NRow] is `.nrow`.
 *
 * The one rule worth stating out loud, because breaking it is how three separate unsized-icon
 * bugs happened: **a slot that takes content takes a typed value, never raw composable
 * content it cannot size.** [NRow]'s leading slot is [NLead], not a `@Composable () -> Unit`.
 */

// ── headings ────────────────────────────────────────────────────────────────────────────

/**
 * A tab's heading and its controls.
 *
 * There is no blurb parameter. Every tab used to carry a standing paragraph under its title
 * explaining what the tab was for; all eight were removed on instruction, and leaving the
 * parameter here would invite them back one screen at a time.
 */
@Composable
fun TabHeader(title: String, actions: (@Composable () -> Unit)? = null) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)
                )
            )
            .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 11.dp),
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.24).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (actions != null) {
            Spacer(Modifier.height(11.dp))
            FlowRowActions(actions)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(actions: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) { actions() }
}

/**
 * A section heading inside a tab, with an optional link on the right.
 *
 * The link is the screen's own verb - Expand, Hide all, Add a folder, Revoke all - and it sits
 * on the heading rather than floating as a button because it acts on the section under it.
 */
@Composable
fun SectionRow(
    label: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(Locale.US),
            fontSize = 9.5.sp,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg3,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                fontSize = 10.sp,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** A centred divider carrying a label. `.rule2` in the mock. */
@Composable
fun RuleLabel(text: String) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 13.dp, start = 15.dp, end = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline))
        Text(
            text.uppercase(Locale.US),
            fontSize = 9.5.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg3,
            modifier = Modifier
                .padding(horizontal = 11.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.sunken)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline))
    }
}

// ── buttons and switches ────────────────────────────────────────────────────────────────

/** `.tbtn`. The header button every tab uses. */
@Composable
fun TabButton(
    label: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) colors.accent else colors.raised)
            .border(
                1.dp,
                if (primary) colors.accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(9.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = if (primary) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            label,
            fontSize = 11.5.sp,
            color = if (primary) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * `.sortpair`. Two or more mutually exclusive choices welded into one control.
 *
 * Used where the choices are questions rather than settings - First seen against Last changed
 * - so that both are readable at once and the screen says which one it is answering.
 */
@Composable
fun SortPair(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.sunken)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, opt ->
            val on = opt == selected
            Text(
                opt,
                fontSize = 11.5.sp,
                maxLines = 1,
                color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
                modifier = Modifier
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
            if (i != options.lastIndex) {
                Box(Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

/** `.sb`. A quiet verb at the end of a row - Copy, Revoke, Kick. */
@Composable
fun SmallBtn(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        maxLines = 1,
        color = Filet.colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** `.tgl`. A labelled switch on its own line. */
@Composable
fun ToggleRow(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!on) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.fg3,
        )
        Box(
            Modifier
                .size(width = 34.dp, height = 19.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) colors.accent else colors.high),
        ) {
            Box(
                Modifier
                    .padding(start = if (on) 17.dp else 2.dp, top = 2.dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.onPrimary else colors.fg3),
            )
        }
    }
}

/** `.chips2`. A label and a short run of exclusive choices. */
@Composable
fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        options.forEach { o ->
            val on = o == selected
            Text(
                o,
                fontSize = 11.sp,
                color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) colors.accent else colors.high)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** `.tick`. A checkbox with a title and an explanation under it. */
@Composable
fun TickRow(on: Boolean, title: String, sub: String? = null, onToggle: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp))
            .background(colors.sunken)
            .clickable(onClick = onToggle)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (on) colors.accent else Color.Transparent)
                .border(
                    1.5.dp,
                    if (on) colors.accent else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Icon(
                    dev.niccc2007.filet.browser.FiletIcons.Check,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Column {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                Text(sub, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.7.sp)
            }
        }
    }
}

// ── rows ────────────────────────────────────────────────────────────────────────────────

/** The pill at the end of a [TRow]. Tone is what the pill is saying, not a colour name. */
enum class PillTone { Plain, Good, Running }

@Composable
fun Pill(text: String, tone: PillTone = PillTone.Plain) {
    val colors = Filet.colors
    val fg = when (tone) {
        PillTone.Plain -> colors.fg3
        PillTone.Good -> colors.good
        PillTone.Running -> colors.accent
    }
    Text(
        text,
        fontSize = 9.5.sp,
        maxLines = 1,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (tone == PillTone.Plain) colors.sunken else Color.Transparent)
            .border(1.dp, fg.copy(alpha = if (tone == PillTone.Plain) 0.22f else 0.38f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** The relative time or size at the end of a [TRow]. `.rt`. */
@Composable
fun Trailing(text: String) {
    Text(text, fontSize = 10.5.sp, maxLines = 1, color = Filet.colors.fg3)
}

/**
 * `.trow2`. The list row used by Home, New files, Bookmarks and Recent.
 *
 * `accent` tints the avatar rather than the row, which is how "this one is new" is said
 * without colouring a whole line of text that still has to be read.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TRow(
    icon: ImageVector,
    name: String,
    sub: String? = null,
    accent: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (accent) MaterialTheme.colorScheme.primaryContainer else colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (accent) colors.accent else colors.fg2,
                modifier = Modifier.size(15.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!sub.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.fg3,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * What sits at the start of an [NRow].
 *
 * This is a sealed type and not a composable slot on purpose. Three separate bugs shipped an
 * SVG into a row that had no rule sizing it, and each one rendered at its intrinsic size -
 * once at 131x93 in a 17px slot. A caller can hand over a kind; it cannot hand over markup.
 */
sealed interface NLead {
    /** A status dot. */
    data class Dot(val state: DotState) : NLead

    /** A file-type thumbnail frame with a fallback glyph. */
    data class Thumb(val icon: ImageVector) : NLead

    /** A plain accent glyph. */
    data class Glyph(val icon: ImageVector) : NLead

    /** Nothing at all, for rows that are only text. */
    data object None : NLead
}

enum class DotState { Idle, Ok, Bad, Down, Up }

@Composable
private fun dotColor(state: DotState): Color {
    val colors = Filet.colors
    return when (state) {
        DotState.Idle -> colors.fg3
        DotState.Ok -> colors.good
        DotState.Bad -> colors.bad
        DotState.Down -> colors.accent
        DotState.Up -> colors.warn
    }
}

/**
 * `.nrow`. The compact row used by Nearby and Remotes.
 *
 * `mono` is for a row whose subtitle is a path or an address - something you read character by
 * character or retype - rather than prose.
 */
@Composable
fun NRow(
    title: String,
    sub: String,
    lead: NLead = NLead.None,
    mono: Boolean = false,
    titleMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (lead) {
            is NLead.Dot -> Box(
                Modifier.size(7.dp).clip(CircleShape).background(dotColor(lead.state))
            )
            is NLead.Thumb -> Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.sunken)
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(lead.icon, null, tint = colors.fg3, modifier = Modifier.size(15.dp)) }
            is NLead.Glyph -> Icon(
                lead.icon, null, tint = colors.accent, modifier = Modifier.size(17.dp)
            )
            NLead.None -> Unit
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (titleMono) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    fontSize = if (mono) 9.5.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    color = colors.fg3,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * `.pips`. One dot per connected device.
 *
 * The blinking light was a single boolean - sharing or not. It now carries how many are
 * connected and what each is doing, because "someone is downloading from me right now" is the
 * thing the light was being asked to say and could not.
 */
@Composable
fun Pips(states: List<DotState>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        states.forEach { s ->
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor(s).copy(alpha = if (s == DotState.Idle) 0.55f else 1f)),
            )
        }
    }
}

/** `.go`. The accent-coloured verb at the end of a row. */
@Composable
fun GoText(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 11.sp,
        maxLines = 1,
        color = Filet.colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * `.scut.pick`. One row in a catalogue of things you can add.
 *
 * A catalogue is not a button. "Add an action" was drawn as a header button twice, and both
 * times it lost the reason the section exists: an action has no row in a folder to long-press,
 * so the list of them IS the way to make one. The row carries its own description because the
 * label alone does not say what tapping it will do.
 */
@Composable
fun PickRow(label: String, desc: String, warn: Boolean = false, onAdd: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .clickable(onClick = onAdd)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                fontSize = 10.sp,
                color = if (warn) colors.warn else colors.fg3,
                lineHeight = 14.sp,
            )
        }
        Text("Add", fontSize = 11.sp, color = colors.accent, maxLines = 1)
    }
}

// ── notes ───────────────────────────────────────────────────────────────────────────────

/**
 * `.warn`. A boxed note that the screen genuinely needs, not a decoration.
 *
 * Every one of these on the redesigned screens is load-bearing: the plain-FTP clear-text
 * warning, the Windows 50 MB WebDAV limit, the captive-portal isolation note. They read as
 * pedantry right up until the moment they are the only explanation for something that looks
 * broken and is not.
 */
@Composable
fun WarnNote(text: String, bad: Boolean = false) {
    val colors = Filet.colors
    val tone = if (bad) colors.bad else colors.warn
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 13.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.34f), RoundedCornerShape(11.dp))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            dev.niccc2007.filet.browser.FiletIcons.Info,
            null,
            tint = tone,
            modifier = Modifier.padding(top = 1.dp).size(15.dp),
        )
        Text(text, fontSize = 11.sp, lineHeight = 16.5.sp, color = colors.fg2)
    }
}

/** `.iso`. The captive-portal note - a warning that is about the network, not the app. */
@Composable
fun IsolationNote(title: String, body: String) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.warn.copy(alpha = 0.15f))
            .padding(11.dp),
    ) {
        Text(title, fontSize = 12.sp, color = colors.warn, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(body, fontSize = 10.5.sp, color = colors.fg2, lineHeight = 14.2.sp)
    }
}

/** `.lbl9`. The tiny all-caps label over an address or a code. */
@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.US),
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        color = Filet.colors.fg3,
        modifier = modifier,
    )
}

/** `.empty2`. What a tab shows when it has nothing, with a reason rather than a shrug. */
@Composable
fun EmptyTab(icon: ImageVector, title: String, line: String) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = colors.fg3.copy(alpha = 0.4f), modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 12.5.sp, color = colors.fg2, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(line, fontSize = 12.sp, color = colors.fg3, lineHeight = 19.2.sp)
    }
}

// ── tiles ───────────────────────────────────────────────────────────────────────────────

/**
 * `.tiles`. A wrapping row whose last row fills rather than leaving a hole.
 *
 * A fixed grid leaves an empty track whenever the count is not a multiple of the column count,
 * which is what he pointed at on Home. Weighting inside a flow row makes each row divide the
 * width it actually has, so three tiles become 2 + 1-that-is-full-width rather than 2 + 1 + a
 * gap. [TileScope.tile] carries the weight, so a caller cannot forget it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Tiles(content: @Composable TileScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) { TileScope(this).content() }
}

@OptIn(ExperimentalLayoutApi::class)
class TileScope(private val scope: androidx.compose.foundation.layout.FlowRowScope) {
    /** One tile. The 150.dp floor is the wrap point; the weight is what fills the last row. */
    @Composable
    fun tile(content: @Composable () -> Unit) {
        with(scope) {
            Box(Modifier.weight(1f).widthIn(min = 150.dp)) { content() }
        }
    }
}

/** A storage tile: a heading, a big number, a caption and an optional capacity bar. */
@Composable
fun StorageTile(
    icon: ImageVector,
    kind: String,
    value: String,
    caption: String,
    fraction: Float? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(13.dp))
            Text(
                kind.uppercase(Locale.US),
                fontSize = 10.sp,
                letterSpacing = 0.9.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            value,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.38).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(caption, fontSize = 10.5.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (fraction != null) {
            Spacer(Modifier.height(8.dp))
            val f = fraction.coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.sunken)) {
                Box(
                    Modifier
                        .fillMaxWidth(f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (f > 0.85f) colors.warn else colors.accent),
                )
            }
        }
    }
}

// ── day headings ────────────────────────────────────────────────────────────────────────

/**
 * `.day`. A collapsible day heading carrying its own count.
 *
 * The count is the whole group's size and not the number of rows drawn, which differ once a
 * group is shut or capped - a heading that says "3 files" over a collapsed group holding 212
 * is worse than no count.
 */
/**
 * A day heading that does not collapse. `.day` without the chevron.
 *
 * Recent has no count and nothing to fold - every entry under it is one line and the list is
 * short by construction. Drawing the chevron anyway would be a control that does nothing,
 * which is the one thing the no-dead-switches rule forbids outright.
 */
@Composable
fun DayLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Filet.colors.fg2,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 5.dp),
    )
}

@Composable
fun DayHeading(label: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .clickable(onClick = onToggle)
            .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (collapsed) dev.niccc2007.filet.browser.FiletIcons.Forward
            else dev.niccc2007.filet.browser.FiletIcons.Expand,
            null,
            tint = colors.fg3,
            modifier = Modifier.size(11.dp),
        )
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.fg2)
        Spacer(Modifier.weight(1f))
        Text("$count file${if (count == 1) "" else "s"}", fontSize = 10.sp, color = colors.fg3)
    }
}
