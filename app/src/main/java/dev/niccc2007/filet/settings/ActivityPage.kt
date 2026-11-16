package dev.niccc2007.filet.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.jobs.JobKind
import dev.niccc2007.filet.jobs.NoticeGroup
import dev.niccc2007.filet.jobs.NoticePrefs
import dev.niccc2007.filet.jobs.Notices
import dev.niccc2007.filet.jobs.label
import dev.niccc2007.filet.jobs.notice
import dev.niccc2007.filet.ui.tabs.HoldHint
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Settings > Activity: which kinds of work raise a card when they start and a message when they
 * end, and how the cards behave.
 *
 * Every row here is drawn from [Notices] and [JobKind.notice], never from a list of its own, so a
 * new kind of job appears on this page the moment it compiles. The page cannot offer a switch for
 * a kind that does not exist or miss one that does.
 *
 * ## Why groups fold
 *
 * Fourteen kinds with two switches each is twenty-eight switches, which reads as a form to fill
 * in. Eight groups with a pair each fit on a screen; a group of several opens to its own kinds,
 * and while closed says how many switches inside are off, so a choice made inside is never
 * hidden by the fold.
 */
object ActivityKeys {
    /**
     * Search anchors for the three card settings. All three live inside one stored value
     * ([dev.niccc2007.filet.data.Prefs.K_NOTICES]); these name the rows, not separate preferences,
     * so each can be found by search and scrolled to.
     */
    const val LIFE = "jobs.notices.life"
    const val PLACE = "jobs.notices.place"
    const val STACK = "jobs.notices.stack"

    /** The "Show over other apps" row: Android's permission, not a preference of Filet's. */
    const val OVERLAY = "nearby.incoming.overlay"
}

/** The column heads over the switches: what the rows are, then Starts and Ends. */
@Composable
internal fun NoticeColumns(title: String) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 10.sp, letterSpacing = 0.3.sp, color = colors.fg3, modifier = Modifier.weight(1f))
        for ((i, head) in listOf("Starts", "Ends").withIndex()) {
            if (i > 0) Spacer(Modifier.width(6.dp))
            Text(
                head,
                fontSize = 10.sp,
                letterSpacing = 0.3.sp,
                color = colors.fg3,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp),
            )
        }
    }
}

/**
 * One group: its name, what is inside or how much of it is off, and its two switches.
 *
 * A group's switch is on while any kind in it is on and is drawn half-lit while only some are, so
 * a group that looks off really is off. Flipping it sets every kind in the group. A group of one
 * has no fold: its switches are that kind's switches.
 */
@Composable
internal fun NoticeGroupRow(
    group: NoticeGroup,
    prefs: NoticePrefs,
    open: Boolean,
    onFold: () -> Unit,
    onChange: (NoticePrefs) -> Unit,
) {
    val colors = Filet.colors
    val kinds = Notices.kindsIn(group)
    if (kinds.isEmpty()) return
    val many = kinds.size > 1
    val endable = kinds.filter { it.notice().ends }
    val startsOn = kinds.count { it !in prefs.startsOff }
    val endsOn = endable.count { it !in prefs.endsOff }
    val off = (kinds.size - startsOn) + (endable.size - endsOn)
    val sub = when {
        !many -> kinds[0].label().second
        off > 0 -> "$off switch${if (off > 1) "es" else ""} off inside"
        else -> kinds.joinToString(" · ") { it.label().first }
    }
    val hint = kinds.joinToString(". ") { k ->
        k.label().first + if (k.notice().ends) ": a card, then a message" else ": a card only"
    } + "."

    HoldHint(hint) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (many) Modifier.clickable(onClick = onFold) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(14.dp)) {
                if (many) {
                    val turn by animateFloatAsState(if (open) 0f else -90f, label = "fold")
                    Icon(
                        FiletIcons.Down,
                        if (open) "Close" else "Open",
                        tint = colors.fg3,
                        modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = turn },
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(group.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                if (sub != null) Text(sub, fontSize = 10.5.sp, lineHeight = 14.sp, color = colors.fg3)
            }
            PairSwitch(on = startsOn > 0, some = startsOn in 1 until kinds.size) { on ->
                onChange(prefs.copy(startsOff = if (on) prefs.startsOff - kinds.toSet() else prefs.startsOff + kinds))
            }
            Spacer(Modifier.width(6.dp))
            if (endable.isEmpty()) {
                Dash()
            } else {
                PairSwitch(on = endsOn > 0, some = endsOn in 1 until endable.size) { on ->
                    onChange(prefs.copy(endsOff = if (on) prefs.endsOff - endable.toSet() else prefs.endsOff + endable))
                }
            }
        }
    }
    if (many && open) {
        for (k in kinds) NoticeKindRow(k, prefs, onChange)
    }
}

/** One kind inside an open group, indented under it. */
@Composable
private fun NoticeKindRow(kind: JobKind, prefs: NoticePrefs, onChange: (NoticePrefs) -> Unit) {
    val colors = Filet.colors
    val (name, note) = kind.label()
    Row(
        Modifier.fillMaxWidth().padding(start = 44.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 12.5.sp, color = colors.fg2)
            if (note != null) Text(note, fontSize = 10.sp, color = colors.fg3)
        }
        PairSwitch(on = kind !in prefs.startsOff, some = false) { on ->
            onChange(prefs.copy(startsOff = if (on) prefs.startsOff - kind else prefs.startsOff + kind))
        }
        Spacer(Modifier.width(6.dp))
        if (!kind.notice().ends) {
            Dash()
        } else {
            PairSwitch(on = kind !in prefs.endsOff, some = false) { on ->
                onChange(prefs.copy(endsOff = if (on) prefs.endsOff - kind else prefs.endsOff + kind))
            }
        }
    }
}

/** A switch that can also say "some of these are on", by drawing on at half strength. */
@Composable
private fun PairSwitch(on: Boolean, some: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.alpha(if (some) 0.55f else 1f)) {
        CompactSwitch(checked = on, onChange = onChange)
    }
}

/** Where an Ends switch would be, for a kind that has never ended with a message. */
@Composable
private fun Dash() {
    Text(
        "—",
        fontSize = 12.sp,
        color = Filet.colors.fg3,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(42.dp),
    )
}
