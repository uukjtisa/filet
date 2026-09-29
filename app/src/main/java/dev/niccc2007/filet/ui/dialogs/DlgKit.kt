package dev.niccc2007.filet.ui.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.FileThumb
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.ui.theme.Filet

/**
 * One shape for every dialogue in the app.
 *
 * ## Why this file exists
 *
 * The app had twenty-two dialogues and they did not agree on anything: some were a bare
 * `AlertDialog` with a column of 13sp `Text` rows, some drew their own scrim and sheet, the
 * titles ran 14sp / 14.5sp / 15sp / 16sp, and the buttons were whatever `TextButton` looks like
 * next to a hand-rolled filled one. Reading them side by side is the argument - nobody chose
 * that, it accumulated.
 *
 * So the sizes here are not invented. Every number is the one in the mock's dialogue CSS, the
 * same way [dev.niccc2007.filet.ui.tabs.TabKit] took its numbers from the tab CSS. Where a
 * comment gives a selector, that is where the value came from and where to change it.
 *
 * ## The one structural rule
 *
 * **A dialogue is a sheet on a narrow screen and a centred card on a wide one, and nothing else
 * about it changes.** Not the padding, not the type, not the order of the buttons - only where
 * it is anchored and which corners are round. Two layouts that differ in more than that are two
 * designs, and the second one drifts.
 *
 * ## What a caller may NOT pass
 *
 * The same contract TabKit settled on: a slot that takes content takes a typed value, never raw
 * composable content it cannot size. [DlgHeader] takes an [ImageVector] and strings, not a
 * `@Composable () -> Unit`, because a header that cannot measure what is in it cannot keep the
 * one shape this file exists to guarantee.
 */

// ── the numbers, in one place ──

/** `.dlg{width:min(420px,100%)}` */
private val CARD_WIDTH = 420.dp

/** `@container screen (max-width: 599px)` - below this a dialogue is a bottom sheet. */
private val SHEET_BELOW = 600.dp

/** `.dlg .db{padding:14px 16px}` */
private val BODY_H = 16.dp
private val BODY_V = 14.dp

/**
 * How a dialogue is anchored.
 *
 * Deliberately not a boolean. A full-screen tool is a third case, not "a very large card", and
 * calling it one is how the APK inspector ended up as a mini dialogue with a scrollbar.
 */
enum class DlgForm {
    /** Sheet on a phone, centred card on a tablet. The default, and what most of them are. */
    AUTO,

    /**
     * Edge to edge, always.
     *
     * For the tool dialogues - the APK inspector, the metadata patcher - which are screens
     * doing a job rather than questions waiting for an answer. A tool in a 420dp card spends
     * its whole life scrolling.
     */
    FULL,
}

/**
 * The frame. Scrim, card, and the sheet-or-card decision.
 *
 * @param onDismiss tapping the scrim or pressing back. Pass a no-op only for a dialogue that
 *   genuinely must be answered; do not pass one to make a dialogue feel important.
 * @param form see [DlgForm].
 * @param dismissOnScrim false for a dialogue with unsaved input in it, where a stray tap on the
 *   scrim would throw away typing. The back gesture still works.
 */
@Composable
fun Dlg(
    onDismiss: () -> Unit,
    form: DlgForm = DlgForm.AUTO,
    dismissOnScrim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Filet.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // The platform decoration is switched off because this draws its own scrim and its
            // own card. Leaving it on gives a dialogue inside a dialogue: two shadows, two
            // corner radii, and a card that cannot reach the bottom edge to be a sheet.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Two layers, and the split is what makes the keyboard work.
        //
        // Bug identified, and it made the rename dialogue unusable: the card was measured
        // against the whole screen and anchored to the bottom edge, so the moment the keyboard
        // came up it sat on top of the field being typed into. The text was there; it was
        // underneath the IME.
        //
        // The scrim has to stay full-bleed - it is the dimming, and dimming that stops where the
        // keyboard starts looks like a rendering fault. The CARD has to live inside what is left.
        // So the scrim is the outer Box and the card gets its own room, inset by
        // `WindowInsets.safeDrawing`, which in Compose already includes the IME along with the
        // status bar, the navigation bar and a display cutout. One inset, all four problems.
        //
        // BoxWithConstraints is INSIDE that padding on purpose: it then reports the height above
        // the keyboard, so `heightIn` shrinks the card and DlgBody scrolls, rather than the card
        // keeping its full height and hanging off the top.
        Box(
            Modifier
                .fillMaxSize()
                // `.scrim{background:rgba(0,0,0,.52)}`
                .background(Color.Black.copy(alpha = 0.52f)),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    // No indication and no ripple: this is a dismiss target, not a button, and
                    // a ripple the size of the screen reads as the app flashing.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = dismissOnScrim,
                        onClick = onDismiss,
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
            // Hoisted out of the nested lambdas below: inside them the BoxWithConstraints
            // receiver is shadowed, and Kotlin will not resolve maxHeight implicitly.
            val roomWide = maxWidth
            val roomTall = maxHeight
            val sheet = form == DlgForm.AUTO && roomWide < SHEET_BELOW
            val full = form == DlgForm.FULL
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = when {
                    full -> Alignment.Center
                    sheet -> Alignment.BottomCenter
                    else -> Alignment.Center
                },
            ) {
                val shape = when {
                    full -> RoundedCornerShape(0.dp)
                    // `.dlg{border-radius:20px 20px 0 0}` on a phone.
                    sheet -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    else -> RoundedCornerShape(18.dp)
                }
                Column(
                    Modifier
                        .then(
                            when {
                                full -> Modifier.fillMaxSize()
                                sheet -> Modifier.fillMaxWidth()
                                else -> Modifier.widthIn(max = CARD_WIDTH).fillMaxWidth()
                            },
                        )
                        // `.dlg{max-height:88%}` as a sheet, `100%` as a card. A dialogue that
                        // can grow past the screen is one whose footer you cannot reach.
                        .then(if (full) Modifier else Modifier.heightIn(max = roomTall * if (sheet) 0.88f else 0.92f))
                        .clip(shape)
                        .background(colors.raised)
                        .then(
                            // A sheet has no bottom border because it has no bottom edge.
                            if (full) Modifier
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
                        )
                        // Swallows taps so the scrim's dismiss does not fire through the card.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = false,
                            onClick = {},
                        ),
                    content = content,
                )
            }
            }
        }
    }
}

/** The tone of a header, which sets its icon tile's colour. */
enum class DlgTone { NORMAL, BAD, WARN }

/**
 * The header: an icon tile, a title, an optional subtitle, and a close button.
 *
 * `.dlg .dh{grid-template-columns:auto 1fr auto; padding:15px 16px 12px}`
 *
 * @param sub the one line under the title. Optional, and left out rather than padded with
 *   something restated from the title - an empty explanation is worse than none.
 * @param onClose null hides the × entirely. Only for a dialogue that must be answered.
 */
@Composable
fun DlgHeader(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    tone: DlgTone = DlgTone.NORMAL,
    onClose: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    val ink = when (tone) {
        DlgTone.NORMAL -> colors.accent
        DlgTone.BAD -> colors.bad
        DlgTone.WARN -> colors.warn
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            // `.dh .di{width:32px;height:32px;border-radius:9px}`
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(ink.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            Text(
                title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = colors.fg3, lineHeight = 15.sp)
            }
        }
        if (onClose != null) {
            Spacer(Modifier.width(11.dp))
            Icon(
                dev.niccc2007.filet.browser.FiletIcons.Close,
                "Close",
                tint = colors.fg3,
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(6.dp),
            )
        }
    }
    // No divider. A rule under a header, a rule over a footer and a footer on its own colour add
    // up to three bands in a card six rows tall - which reads as three panels stacked rather than
    // one popup. The spacing does the separating now.
}

/**
 * The scrolling middle.
 *
 * `.dlg .db{padding:14px 16px;overflow:auto;flex:1;min-height:0}`
 *
 * Scrolls by default because the alternative fails silently: a body that outgrows the card
 * without scrolling pushes the footer off the bottom, and the dialogue loses its buttons.
 *
 * @param scroll false only when the body IS a list with its own scrolling, since nesting two
 *   scrollers in the same direction gives the inner one zero height.
 */
@Composable
fun ColumnScope.DlgBody(
    scroll: Boolean = true,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .then(if (padded) Modifier.padding(horizontal = BODY_H, vertical = BODY_V) else Modifier),
        content = content,
    )
}

/**
 * The footer.
 *
 * `.dlg .df{padding:12px 16px 14px;border-top:1px solid var(--line-soft);background:var(--surface)}`
 *
 * Buttons go last-is-primary, and on a phone they stretch to share the width
 * (`@container (max-width:599px){ .df .btn{flex:1} }`) because a 9px-padded button at the far
 * right of a 400dp sheet is a thumb-stretch for the action you almost always want.
 */
@Composable
fun ColumnScope.DlgFooter(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // Same surface as the body and no rule above it. The buttons are their own shapes and
            // do not need a strip behind them to be found.
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The kinds of button a footer has. Three, so nothing has to reach for a raw colour. */
enum class BtnKind { PLAIN, PRIMARY, DANGER }

/**
 * A footer button.
 *
 * `.dlg .btn{padding:9px 15px;border-radius:10px;font-size:12.5px}`
 *
 * @param fill true makes it share the row's width, which is what a sheet does on a phone. The
 *   caller decides because only the caller knows whether the row has a spacer in it.
 */
@Composable
fun RowScope.DlgBtn(
    label: String,
    kind: BtnKind = BtnKind.PLAIN,
    enabled: Boolean = true,
    fill: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val bg = when (kind) {
        BtnKind.PLAIN -> colors.raised
        BtnKind.PRIMARY -> colors.accent
        BtnKind.DANGER -> colors.bad
    }
    val fg = when (kind) {
        BtnKind.PLAIN -> MaterialTheme.colorScheme.onSurface
        // Both filled kinds carry light text. `--accent-fg` in the mock, and the danger button
        // is white on red in every theme because a red that is light enough to need dark text
        // is not red enough to read as danger.
        else -> Color(0xFF10100F)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .then(if (fill) Modifier.weight(1f) else Modifier)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(
                if (kind == BtnKind.PLAIN) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                else Modifier,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (kind == BtnKind.PLAIN) FontWeight.Normal else FontWeight.Medium,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            maxLines = 1,
        )
    }
}

/** Pushes the buttons after it to the right. `.dlg .df .sp{flex:1}` */
@Composable
fun RowScope.DlgSpacer() = Spacer(Modifier.weight(1f))

// ── the parts a body is built from ──

/**
 * A labelled field. `.fld>label{font-size:10px;text-transform:uppercase;letter-spacing:.1em}`
 *
 * The label is drawn in small caps above the input rather than as placeholder text inside it,
 * because a placeholder disappears the moment someone types and then the field has no name.
 */
@Composable
fun DlgField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String? = null,
    password: Boolean = false,
    numeric: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    /**
     * Drops the trailing gap, for a field whose caption sits directly beneath it.
     *
     * Without this the 13dp below the box pushes its own caption away from it, and the line
     * describing the field reads as belonging to whatever comes next.
     */
    tight: Boolean = false,
) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(bottom = if (tight) 0.dp else 13.dp)) {
        // A blank label draws nothing at all. It used to draw an empty Text plus its 6dp spacer,
        // so a field under a section heading - where the heading already names it - left a gap
        // the height of a label with nothing in it.
        if (label.isNotBlank()) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.0.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.fg3,
            )
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            ),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation =
                if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(colors.sunken)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        )
        if (hint != null) {
            Spacer(Modifier.height(5.dp))
            Text(hint, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 15.sp)
        }
    }
}

/**
 * A tick that is a row, not a box floating beside a label.
 *
 * `.tick{grid-template-columns:20px 1fr;padding:10px 11px;border-radius:11px}`
 *
 * The whole row is the target and the whole row shows the state. This is the specific complaint
 * about the "always open with this" checkbox: the box and its text were in different places and
 * neither explained the other.
 */
@Composable
fun DlgTick(
    on: Boolean,
    label: String,
    sub: String? = null,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (on) colors.accent.copy(alpha = 0.09f) else colors.sunken)
            .border(1.dp, if (on) colors.accent else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
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
                    tint = Color(0xFF10100F),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) {
                Spacer(Modifier.height(3.dp))
                Text(sub, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * A quiet line under a field, saying what was understood.
 *
 * Not a [DlgWarn]. A warning box is for something standing between the reader and a working
 * result; feedback on what they just typed is neither a warning nor news, and drawing it as one
 * is how a form ends up a wall of yellow boxes that all read as problems.
 */
@Composable
fun DlgCaption(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = Filet.colors.fg3,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

/**
 * A warning block inside a body.
 *
 * `.warn{grid-template-columns:18px 1fr;padding:11px;border-radius:11px}`
 *
 * One block, one sentence. The reason this is a component and not a free `Text` is that the
 * loose version grew into paragraphs - see [dev.niccc2007.filet.ui.tabs.NoteStack] for what
 * happened when several of them landed on one screen.
 *
 * **And one per surface.** Four of these on one form, each short enough to pass the character
 * budget, still reads as a wall of yellow boxes that all look like problems. A banner is for the
 * thing standing between the reader and a working result. Feedback on what was typed is
 * [DlgCaption]; a standing fact worth having once is a collapsed [DlgNote].
 */
@Composable
fun DlgWarn(text: String, bad: Boolean = false) {
    val colors = Filet.colors
    val ink = if (bad) colors.bad else colors.warn
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 13.dp)
            .clip(shape)
            .background(ink.copy(alpha = 0.12f))
            .border(1.dp, ink.copy(alpha = 0.35f), shape)
            .padding(11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            dev.niccc2007.filet.browser.FiletIcons.Warn,
            null,
            tint = ink,
            modifier = Modifier.padding(top = 1.dp).size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 11.sp, color = colors.fg2, lineHeight = 16.5.sp)
    }
}

/**
 * A key and a value, for the rows that are facts rather than controls.
 *
 * `.kv dd{font-variant-numeric:tabular-nums;word-break:break-all}` - tabular figures so a
 * column of sizes lines up, and breaking anywhere because a path has no spaces to break at.
 */
@Composable
fun DlgKv(key: String, value: String, mono: Boolean = false) {
    val colors = Filet.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, fontSize = 12.sp, color = colors.fg3, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A chooser row: icon tile, name, detail, optional tag.
 *
 * `.pick{grid-template-columns:30px 1fr auto;padding:10px 11px;border-radius:11px}`
 *
 * Shared by Open With and the uncertain-type chooser, which the mock calls out explicitly -
 * they are the same question ("which of these should read this file") asked for two reasons,
 * and drawing them differently would imply they are not.
 *
 * @param detail the second line. Monospaced, because it is a package name or a path.
 * @param tag a short right-hand label. `ours = true` accents it, for a handler that is Filet's
 *   own rather than another app's - the one distinction that actually matters in this list.
 */
@Composable
fun DlgPick(
    icon: ImageVector,
    label: String,
    detail: String? = null,
    tag: String? = null,
    ours: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.sel else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (selected) colors.accent else colors.fg2,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    fontSize = 10.sp,
                    color = colors.fg3,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                tag.uppercase(),
                fontSize = 9.5.sp,
                letterSpacing = 0.6.sp,
                color = if (ours) colors.accent else colors.fg3,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (ours) colors.accent.copy(alpha = 0.18f) else colors.sunken)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * A plain tappable row, for a dialogue that is a short list of actions.
 *
 * `.pick` without the icon tile. What [DlgPick] is for a choice, this is for a verb - and it
 * keeps an icon because a column of bare text was what the long-press sheet used to be, and a
 * list of five unadorned sentences is slower to read than five glyphs.
 */
@Composable
fun DlgAction(
    icon: ImageVector,
    label: String,
    sub: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val ink = when {
        !enabled -> colors.fg3.copy(alpha = 0.45f)
        danger -> colors.bad
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = ink)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 10.5.sp, color = colors.fg3, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * A titled break between groups in a body.
 *
 * The specific fix for "i need it to have a good separator": a rule with no label is a gap, and
 * a gap does not say what changed across it.
 */
@Composable
fun DlgSection(label: String) {
    val colors = Filet.colors
    Row(
        // Its own air above. A caller adding a Spacer before one of these stacks 14dp onto the
        // 13dp a field already leaves below itself, and the form grows a 37dp hole between every
        // group.
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            fontSize = 9.5.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.fg3,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(color = colors.lineSoft, modifier = Modifier.weight(1f))
    }
}

/**
 * The row of icon buttons across the top of a context menu.
 *
 * `.ctxbar{display:flex;gap:4px;padding:5px}` and `.cb2{flex:1;height:38px}`
 *
 * This is the row the first redesign dropped. It is the whole reason the menu is quick: cut,
 * copy, paste, rename and delete are the things reached most, and moving them into the list
 * makes the list five entries longer for the five things that should take one tap. Windows 11
 * puts exactly this row at the top of its context menu, which is the reference that was asked
 * for.
 */
@Composable
fun DlgIconBar(content: @Composable RowScope.() -> Unit) {
    val colors = Filet.colors
    Column {
        Row(
            Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
        HorizontalDivider(color = colors.lineSoft)
    }
}

/**
 * One button in a [DlgIconBar].
 *
 * The label is drawn under the glyph at 9px rather than only on hover, because a phone has no
 * hover and five glyphs in a row would otherwise be five guesses. The mock renders it as a
 * hover tooltip because a mock has a mouse; this is the same information, always visible.
 */
@Composable
fun RowScope.DlgIconBtn(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    val alpha by animateFloatAsState(if (enabled) 1f else 0.34f, label = "iconBtn")
    val ink = if (danger) colors.bad else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = ink.copy(alpha = alpha), modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 9.sp,
            color = colors.fg3.copy(alpha = alpha),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The header every popup wears: the name, the path, the facts, and the file itself.
 *
 * ## What changed and why
 *
 * It used to be a title with an icon TILE on the left and a close button on the right, under a
 * divider, over a body, over a footer on its own background. Three bands in a popup six rows
 * tall, and the close reading as a thing bolted to a strip rather than a corner of a card.
 *
 * Now: one surface, no dividers, and the right-hand slot holds **the file** rather than a
 * category glyph. A long press is about one specific file, and its own thumbnail identifies it
 * faster than any icon can - see [FileThumb], which falls back to the extension as a word for
 * types the icon set has no drawing for, and to the kind glyph for the rest.
 *
 * ## The close
 *
 * Tucked into the card's top-right corner, overlapping the thumbnail, at 19dp with a scrim disc
 * behind it. That placement is deliberate on two counts: it costs **no layout space at all**,
 * which was the complaint, and it sits at the corner of the CARD rather than on the row, which is
 * where a dismiss belongs and keeps it from reading as "delete this file".
 *
 * @param thumb the file this popup is about, for the thumbnail. Null for a popup that is not
 *   about one file - a multi-selection, or a question with no subject - and the slot then
 *   collapses rather than showing a placeholder for nothing.
 */
@Composable
fun DlgCtxHead(
    name: String,
    path: String,
    meta: String? = null,
    thumb: VNode? = null,
    onClose: (() -> Unit)? = null,
) {
    val colors = Filet.colors
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (path.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        path,
                        fontSize = 10.5.sp,
                        color = colors.fg3,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        // Ellipsised at the START: the end of a path is the folder the file is
                        // actually in, and that is the half that identifies it. This popup is
                        // routinely opened from a search result, where the file is by definition
                        // somewhere other than where you are standing.
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
                if (meta != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(meta, fontSize = 10.5.sp, color = colors.fg3)
                }
            }
            if (thumb != null) {
                // Room for the close to overlap without covering the picture: the thumbnail keeps
                // its 44dp and the gap to its right is the card's own padding.
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    FileThumb(
                        node = thumb,
                        size = 44.dp,
                        fallbackTint = colors.fg2,
                        // A 44dp slot holding a 22dp glyph, so a file with no preview is not a
                        // huge icon. The monogram scales off the slot, not the glyph.
                        glyphSize = 22.dp,
                    )
                }
            }
        }
        if (onClose != null) {
            Icon(
                FiletIcons.Close,
                "Close",
                tint = colors.fg2,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // INSET from the corner, not offset past it. The card Column is clipped to
                    // its rounded shape, so a child offset outward is not overlapping the edge -
                    // it is outside the bounds and discarded, which is why this vanished. Six
                    // in from the top and the end lands it over the thumbnail's top-right
                    // corner, which is the look, and nothing below it moves to make room, which
                    // was the point.
                    .padding(top = 6.dp, end = 6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    // Opaque, not 92%: it sits on a photograph, and a translucent disc over a
                    // bright thumbnail leaves the glyph fighting whatever is behind it.
                    .background(colors.raised)
                    .clickable(onClick = onClose)
                    .padding(5.dp),
            )
        }
    }
}

/**
 * A warning that is one glyph until somebody asks for it.
 *
 * The rule this exists to enforce: **a dialogue is not a place to explain things.** If a caution
 * genuinely has to be reachable - a permission being granted, a write that cannot be undone - it
 * goes in here, shut, as a line and a chevron. It does not go in the body as a paragraph, and it
 * does not go in the header's subtitle.
 *
 * The test for whether a sentence belongs in a dialogue at all: would somebody who already knows
 * what this dialogue does read it twice? If not, it is not helping them, and it is costing every
 * reader the space and the moment.
 *
 * @param title the one line that shows when it is shut. Not a summary of the note - a name for
 *   it, so it can be ignored on sight.
 */
@Composable
fun DlgNote(title: String, body: String) {
    val colors = Filet.colors
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(shape)
            .background(colors.sunken)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                dev.niccc2007.filet.browser.FiletIcons.Info,
                null,
                tint = colors.fg3,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(title, fontSize = 11.5.sp, color = colors.fg2, modifier = Modifier.weight(1f))
            Icon(
                if (open) FiletIcons.Up else FiletIcons.Sort,
                null,
                tint = colors.fg3,
                modifier = Modifier.size(13.dp),
            )
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            Text(body, fontSize = 11.sp, color = colors.fg3, lineHeight = 16.sp)
        }
    }
}
