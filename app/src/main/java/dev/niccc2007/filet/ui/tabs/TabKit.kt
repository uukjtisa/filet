package dev.niccc2007.filet.ui.tabs

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import dev.niccc2007.filet.ui.highlightPulse
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.niccc2007.filet.browser.chosenForPick
import dev.niccc2007.filet.ui.theme.Capacity
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import java.util.Locale
import androidx.compose.material3.PlainTooltip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
    /**
     * A trailing word that is a label and not a control.
     *
     * Separate from [action] on purpose. Passing an action with an empty lambda to get the
     * same appearance draws something that looks pressable and is not, which is the dead
     * switch R1 forbids - and it is an easy thing to reach for, because the two render in
     * nearly the same place.
     *
     * Declared before [onAction] so the trailing-lambda slot stays the action. Putting it
     * last silently rebinds every `SectionRow(..) { ... }` call to this parameter.
     */
    note: String? = null,
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
        } else if (note != null) {
            Text(note, fontSize = 10.sp, color = colors.fg3)
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
/**
 * What a pill is saying.
 *
 * [Bad] arrived with the remote probe: there was no tone for "this was tried and it failed",
 * so a failed dial would have had to borrow [Running]'s accent - which is the colour for
 * something in progress, and reading a failure as activity is the wrong way round.
 */
enum class PillTone { Plain, Good, Running, Bad }

@Composable
fun Pill(text: String, tone: PillTone = PillTone.Plain) {
    val colors = Filet.colors
    val fg = when (tone) {
        PillTone.Plain -> colors.fg3
        PillTone.Good -> colors.good
        PillTone.Running -> colors.accent
        PillTone.Bad -> colors.warn
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
    /**
     * The file this row stands for, when there is one.
     *
     * Given it, the avatar becomes the file's own picture - a photo, a video frame, an album
     * cover, an app's launcher icon - and falls back to [icon] while it decodes or when the
     * type has no preview. A list of type glyphs tells you what kind of file each row is,
     * which you already knew from the name; a list of thumbnails tells you which one it is.
     */
    thumbOf: VNode? = null,
    /** Part of the pane's selection, drawn the way a selected row in the file list is. */
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.sel) else Modifier)
            // The file this row stands for is also what a live pick would take, so the row
            // says so the way a selected row in the file list does. See `chosenForPick`.
            .chosenForPick(thumbOf?.path)
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
            val tint = if (accent) colors.accent else colors.fg2
            if (thumbOf != null) {
                // The same component the file list uses, so a thumbnail decoded for one
                // surface is already in the cache for the other.
                dev.niccc2007.filet.browser.FileThumb(
                    node = thumbOf,
                    size = 30.dp,
                    fallbackTint = tint,
                    glyphSize = 15.dp,
                )
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            // Lit where the pane's search box matched it, in any list that narrows by it.
            val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
            val ink = colors.accent
            dev.niccc2007.filet.browser.ListNameText(
                text = remember(name, query, ink) { dev.niccc2007.filet.ui.highlighted(name, query, ink) },
                color = MaterialTheme.colorScheme.onSurface,
                startSp = 12.5f,
                lines = 1,
                isDir = thumbOf?.isDir ?: false,
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun NRow(
    title: String,
    sub: String,
    lead: NLead = NLead.None,
    mono: Boolean = false,
    titleMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * What tapping the row does, or null when it is only something to look at.
     *
     * There was no such parameter, so every row built from this was inert - including a saved
     * network place, which had an Edit button and no way at all to OPEN it. A row that looks
     * like a list item and does nothing when tapped reads as the app being broken.
     */
    onClick: (() -> Unit)? = null,
    /**
     * What a long press does, or null for nothing.
     *
     * Rows in this app answer a long press everywhere else - a file, a playlist, a track - and
     * these did not, so the actions that do not fit in a row's trailing buttons had no home at
     * all. See the Remotes screen, where Forget and Duplicate live.
     */
    onLongClick: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth()
            .then(
                when {
                    onClick == null && onLongClick == null -> Modifier
                    // `combinedClickable` with a null onClick still consumes the tap, so the
                    // plain case keeps using `clickable` - a row that looks inert and swallows
                    // presses is worse than one that is plainly inert.
                    else -> Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                },
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
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
            // Lit where the pane's search box matched, name or address.
            val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
            dev.niccc2007.filet.browser.ListNameText(
                text = dev.niccc2007.filet.ui.highlighted(title, query, colors.accent),
                color = MaterialTheme.colorScheme.onSurface,
                startSp = 12.5f,
                lines = 1,
                isDir = true,
                fontFamily = if (titleMono) FontFamily.Monospace else FontFamily.Default,
            )
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                dev.niccc2007.filet.browser.ListNameText(
                    text = dev.niccc2007.filet.ui.highlighted(sub, query, colors.accent),
                    color = colors.fg3,
                    startSp = if (mono) 9.5f else 10f,
                    lines = 1,
                    isDir = true,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
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
            dev.niccc2007.filet.browser.ListNameText(
                text = dev.niccc2007.filet.ui.highlighted(label, dev.niccc2007.filet.ui.LocalPaneQuery.current, colors.accent),
                color = androidx.compose.material3.LocalContentColor.current,
                startSp = 13f,
                lines = 1,
                isDir = true,
            )
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

/**
 * A stack of notes behind one line you can open.
 *
 * Warnings that are each individually worth keeping stop being read when there are three of
 * them in a column: the card turns into a page of caveats and the actual controls get pushed
 * off the screen. Collapsed, the count is the signal - "there are things to know here" - and
 * the words are one tap away for the moment somebody needs them.
 *
 * Shut by default. A note that opens itself is the wall again with an extra step.
 */
@Composable
fun NoteStack(label: String, notes: List<String>) {
    if (notes.isEmpty()) return
    val colors = Filet.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .clickable { open = !open }
                .padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                dev.niccc2007.filet.browser.FiletIcons.Info,
                null,
                tint = colors.fg3,
                modifier = Modifier.size(13.dp),
            )
            Text(
                if (notes.size == 1) label else "$label (${notes.size})",
                fontSize = 11.sp,
                color = colors.fg3,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) dev.niccc2007.filet.browser.FiletIcons.Expand
                else dev.niccc2007.filet.browser.FiletIcons.Forward,
                null,
                tint = colors.fg3,
                modifier = Modifier.size(11.dp),
            )
        }
        if (open) {
            for (n in notes) {
                Text(
                    "·  $n",
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    color = colors.fg3,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
            }
        }
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

/**
 * `.empty2`. What a tab shows when it has nothing.
 *
 * [line] is for the single action that fills the list, in a few words, and nothing else. It
 * used to carry an explanation of what the tab was for, on every tab - which is a paragraph
 * nobody reads twice and which made the app sound like it was selling itself. Why a screen
 * exists belongs in the source, next to the code that makes it exist.
 */
@Composable
fun EmptyTab(icon: ImageVector, title: String, line: String? = null) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = colors.fg3.copy(alpha = 0.4f), modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 12.5.sp, color = colors.fg2, fontWeight = FontWeight.Medium)
        if (!line.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(line, fontSize = 12.sp, color = colors.fg3, lineHeight = 19.2.sp)
        }
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
    /**
     * One tile. [min] is the wrap point; the weight is what fills the last row.
     *
     * The floor is a parameter because the same layout serves tiles of two sizes: a storage
     * card carries a heading, a figure and a caption and needs 150dp to say them, while a
     * category is an icon and one word and fits three to a phone's width. One flow row with a
     * floor per caller beats a second grid that would drift from this one.
     */
    @Composable
    fun tile(min: androidx.compose.ui.unit.Dp = 150.dp, content: @Composable () -> Unit) {
        with(scope) {
            Box(Modifier.weight(1f).widthIn(min = min)) { content() }
        }
    }
}

/**
 * Shorten a volume label for the tile heading.
 *
 * "Internal storage" under a section already headed STORAGE is the word "storage" twice, and
 * at the tile's width the second one costs the first: it rendered as "INTERN...", which names
 * nothing. Dropping the redundant word is better than ellipsising the informative one.
 *
 * Only the trailing word is dropped, and only when something is left. A volume actually named
 * "Storage" keeps its name rather than becoming empty.
 */
fun shortVolumeLabel(label: String): String {
    val trimmed = label.trim()
    for (suffix in listOf(" storage", " Storage", " STORAGE")) {
        if (trimmed.endsWith(suffix) && trimmed.length > suffix.length) {
            return trimmed.dropLast(suffix.length).trim()
        }
    }
    return trimmed
}

/** A storage tile: a heading, a big number, a caption and an optional capacity bar. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StorageTile(
    icon: ImageVector,
    kind: String,
    value: String,
    caption: String,
    fraction: Float? = null,
    onHide: (() -> Unit)? = null,
    /**
     * A small dot beside the label. Null draws none.
     *
     * For a place whose reachability is a live fact rather than a stored setting - a mounted
     * network drive is either answering right now or it is not, and that is worth showing where
     * the eye already is.
     */
    live: Boolean? = null,
    /** A hold on the tile. Null leaves it a plain tap target. */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 11.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(13.dp))
            if (live != null) {
                // Breathing while it is reachable, still when it is not. A blink says "being
                // checked"; a steady dot on an unreachable drive says nothing at all.
                val pulse = rememberInfiniteTransition(label = "live")
                val alpha by pulse.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "liveAlpha",
                )
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (live) colors.good.copy(alpha = alpha)
                            // Neutral, not red. Nothing has failed - it is simply not answering,
                            // and red would read as a fault somebody has to go and fix.
                            else colors.fg3.copy(alpha = 0.55f),
                        ),
                )
            }
            // The label takes the slack and the close sits after it, so the close is flush
            // right at every tile width. Weighting BOTH - which is what this did - splits the
            // slack between them, and the button drifts inward the moment a tile widens to
            // fill a row. Invisible at 150dp, obvious at full width.
            Text(
                shortVolumeLabel(kind).uppercase(Locale.US),
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Hiding has to stay reachable from the tile. The card this replaced carried it,
            // and a card you can hide from one place and only restore from another is the
            // one-way door the restore link at the section heading exists to undo.
            if (onHide != null) {
                Icon(
                    dev.niccc2007.filet.browser.FiletIcons.Close,
                    "Hide this card",
                    tint = colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onHide)
                        .padding(3.dp)
                        .size(11.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
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
        // The bar's space is ALWAYS taken, drawn or not.
        //
        // A tile with no figure used to omit it entirely and end up visibly shorter than the one
        // beside it, which in a two-up row reads as the card having failed to finish loading.
        // An empty track is honest - there is a bar here and it has nothing to say - and it keeps
        // the row even.
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.sunken)) {
            if (fraction != null) {
                val f = fraction.coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth(f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        // One solid colour, taken from the fullness itself rather than from the
                        // accent. See Capacity.
                        .background(Capacity.colourAt(f)),
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

// ── hold-to-show hints ──────────────────────────────────────────────────────────────────────

/**
 * Called when an opened hint closes, so a screen can retire its "hold for hints" line. On close
 * rather than on open: the line sits above the list, and taking it away while a bubble is up
 * would move the held row out from under the bubble. Provided by the screen; a no-op elsewhere.
 */
val LocalHintOpened = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/**
 * What a row does, shown when it is held - and on hover with a mouse - instead of printed
 * under it.
 *
 * Settings printed every row's description under its title, which turned a screen of controls
 * into a screen of prose. The description is still there for whoever wants it, one hold away,
 * and the row is one line for everyone else.
 *
 * The bubble sits above the row: the thumb comes from below and would cover anything under it.
 * It moves below only when there is no room above. It stays after the finger lifts and goes on
 * the next touch anywhere else, scrolling included.
 *
 * The hold is this composable's own, not the tooltip's. M3's waits in the Initial pass and only
 * asks whether the finger left the row - and in a scrolling list the row moves WITH the finger,
 * so half a second of slow scrolling counted as a hold. It opened a hint, swallowed the scroll's
 * next event, which stopped the list dead, and its popup took focus, so the fling after it was
 * spent dismissing the bubble instead of moving the list. That was the jump at the end of a
 * Settings tab. So a hold here is half a second with nothing having consumed the pointer -
 * a scroll consumes, so a scroll is never a hold - and the bubble never takes focus, so the
 * touch that dismisses it also scrolls. The release that ends a real hold is swallowed, so a
 * hold never also flips the switch or picks a chip, and an ordinary tap is untouched.
 *
 * For a screen reader the hint is the content description of the held area, so it is read
 * rather than lost, and the hold is offered as the long-press action.
 *
 * A hold is invisible until somebody is told about it, so a row can also put a [HintInfo] after
 * its name: a small circle that opens the same bubble on a tap.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HoldHint(hint: String?, key: String? = null, content: @Composable () -> Unit) {
    // The mark a search result leaves on the row it brought into view. See LocalSettingPulse.
    val pulse = LocalSettingPulse.current?.takeIf { key != null && it.first == key }?.second
    if (hint.isNullOrBlank()) {
        if (key == null) content() else Box(Modifier.highlightPulse(pulse)) { content() }
        return
    }
    val colors = Filet.colors
    val state = androidx.compose.material3.rememberTooltipState(isPersistent = true)
    val opened = androidx.compose.runtime.rememberUpdatedState(LocalHintOpened.current)
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.isVisible) {
        if (state.isVisible) shown = true else if (shown) opened.value()
    }
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(
            androidx.compose.material3.TooltipAnchorPosition.Above,
            6.dp,
        ),
        // Both off, and both on purpose - see above. The gestures are [holdToShow]'s.
        focusable = false,
        enableUserInput = false,
        tooltip = {
            PlainTooltip(
                caretShape = androidx.compose.material3.TooltipDefaults.caretShape(),
                maxWidth = 260.dp,
                shape = RoundedCornerShape(10.dp),
                contentColor = MaterialTheme.colorScheme.onSurface,
                containerColor = colors.raised,
                shadowElevation = 6.dp,
            ) {
                Column {
                    Text(hint, fontSize = 12.sp, lineHeight = 16.sp)
                    // The setting's own name in the settings file, last and small: what to
                    // look for in filet.json, and what a search for it will match.
                    if (key != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            key,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = colors.fg3,
                        )
                    }
                }
            }
        },
        state = state,
    ) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val show = androidx.compose.runtime.remember(state, scope) { { scope.launch { state.show() }; Unit } }
        val hide = androidx.compose.runtime.remember(state) { { state.dismiss() } }
        Box(
            Modifier
                .highlightPulse(pulse)
                .holdToShow(show, hide)
                .semantics {
                    contentDescription = hint
                    onLongClick(label = "Show details") { show(); true }
                },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalHintShow provides show) { content() }
        }
    }
}

/**
 * Half a second's hold opens the hint; a mouse resting on it opens it too, and leaving closes it.
 *
 * The hold is watched on the Final pass, after the row's own children have had the event. A
 * list that starts scrolling consumes it there, and a consumed pointer ends the wait, so no
 * amount of slow scrolling can turn into a hold. Once a hold has fired the rest of the gesture
 * is consumed on the Initial pass, ahead of the row, which is what keeps it from also clicking.
 */
private fun Modifier.holdToShow(show: () -> Unit, hide: () -> Unit): Modifier = this
    .pointerInput(show) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Final).changes
                        .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!change.pressed || change.isConsumed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
            if (ended != null) return@awaitEachGesture
            show()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
    .pointerInput(show) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter -> show()
                    PointerEventType.Exit -> hide()
                }
            }
        }
    }

/**
 * The setting a search result just went to, by key, with a fresh tick per visit. A settings row
 * whose key it is - every row passes its key through [HoldHint] - washes briefly so the eye
 * lands on it. Null everywhere else.
 */
val LocalSettingPulse = androidx.compose.runtime.compositionLocalOf<Pair<String, Long>?> { null }

/** Opens the enclosing [HoldHint]'s bubble. Null outside one, where [HintInfo] draws nothing. */
private val LocalHintShow = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The circle after a setting's name that opens its hint on a tap.
 *
 * 14dp on the glass and 40dp under the finger. The layout reports only the glyph's own room, so
 * a row's height does not grow by a target nobody can see; the rest of the 40dp hangs over the
 * row, which nothing clips, and takes the tap before the row does. Hidden from a screen reader,
 * which already reads the hint as the row's description.
 */
@Composable
fun HintInfo() {
    val show = LocalHintShow.current ?: return
    Box(
        Modifier
            .layout { measurable, _ ->
                val target = 40.dp.roundToPx()
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(target, target))
                val w = 22.dp.roundToPx()
                val h = 14.dp.roundToPx()
                layout(w, h) { placeable.place((w - target) / 2, (h - target) / 2) }
            }
            .clearAndSetSemantics {}
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = show,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            dev.niccc2007.filet.browser.FiletIcons.Info,
            contentDescription = null,
            tint = Filet.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
    }
}
