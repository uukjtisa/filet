package dev.niccc2007.filet.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.DetailPart
import dev.niccc2007.filet.browser.EntryDisplay
import dev.niccc2007.filet.browser.FileRow
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.FolderBytes
import dev.niccc2007.filet.browser.FolderTotal
import dev.niccc2007.filet.browser.LocalFolderBytes
import dev.niccc2007.filet.browser.LocalSampleInside
import dev.niccc2007.filet.browser.PaneMetrics
import dev.niccc2007.filet.browser.RowDetails
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.data.ViewStep
import dev.niccc2007.filet.icons.Inside
import dev.niccc2007.filet.icons.Thing
import dev.niccc2007.filet.ui.tabs.HoldHint
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes
import java.util.Calendar

/**
 * The top of Row details: a preview drawn by the list itself, then what goes on the line under
 * a name.
 *
 * ## The preview is the list's row, not a picture of it
 *
 * It used to be two lines of monospace text, "Report.pdf" over "2 Nov 2026 · 123 KB". That was
 * a preview of the STRING, and the string was never the question: the question is what a list
 * looks like, and a list has icons, a name that fades rather than dots, a column that lines up
 * and a second line that only some views draw. A preview that drew none of those could agree with
 * itself and still mislead about every one of them.
 *
 * So the sample entries go through [FileRow], the composable every list draws with, fed the
 * [EntryDisplay] being edited. Nothing here re-implements a row, so nothing can drift from one.
 * The two things a sample cannot answer from a drive are handed in instead of faked inside the
 * row: what a folder holds ([LocalSampleInside]) and a folder's settled total ([LocalFolderBytes]),
 * without which the total would pulse for ever waiting on a crawl of a path that does not exist.
 *
 * ## Why the preview has a view switch
 *
 * The line under the name is drawn only in Comfortable. Previewed in the lists' own view - List,
 * by default - every change to the line would change nothing visible, which reads as the
 * setting being broken. The switch shows the other views without changing the lists, the tick
 * says which view the lists use, and the note says plainly when the line is hidden and offers to
 * change that.
 *
 * ## Why one list of tick boxes
 *
 * The line takes any number of parts in an order, so each part needs on, off and a place. One
 * list holds all three: the parts that are on come first in their order with arrows to move them,
 * the rest follow unticked. The earlier shape split them into two lists with add and remove
 * buttons, which made turning a part on and off two different gestures in two different places.
 */
@Composable
fun RowDetailCard(
    entry: EntryDisplay,
    viewStep: Int,
    onViewStep: (Int) -> Unit,
    onChange: (EntryDisplay) -> Unit,
) {
    val parts = entry.parts
    fun write(next: List<DetailPart>) = onChange(entry.copy(parts = next))

    val samples = remember { Sample.rows(System.currentTimeMillis()) }
    // A grid step has no rows to preview; it shows as List, which is what its rows become in a
    // tree or a split.
    val yours = ViewStep.of(viewStep).let { if (it in PREVIEW_STEPS) it else ViewStep.LIST }
    var picked by remember { mutableStateOf<ViewStep?>(null) }
    val shown = picked ?: yours

    Column(Modifier.fillMaxWidth()) {
        Preview(
            entry = entry,
            samples = samples,
            shown = shown,
            yours = yours,
            onPick = { picked = if (it == yours) null else it },
            onUseComfortable = {
                picked = null
                onViewStep(ViewStep.COMFORTABLE.ordinal + 1)
            },
        )

        // Keyed so Settings search finds the line's parts under this heading's name.
        HoldHint("Folder total, file size, item count, date and type, ticked on or off and put in order.", key = Prefs.K_E_PARTS) {
            SettingsSubHead(
                "Under the name",
                "In order, joined by dots. A part that doesn't apply to a row is left out of it.",
            )
        }
        val off = remember(parts) { DetailPart.entries.filterNot { it in parts } }
        for ((i, part) in parts.withIndex()) {
            PartRow(
                part = part,
                on = true,
                sample = Sample.valueOf(part, samples, entry),
                onToggle = { write(RowDetails.toggled(parts, part)) },
                onUp = if (i > 0) ({ write(RowDetails.moved(parts, i, i - 1)) }) else null,
                onDown = if (i < parts.size - 1) ({ write(RowDetails.moved(parts, i, i + 1)) }) else null,
            )
        }
        for (part in off) {
            PartRow(
                part = part,
                on = false,
                sample = Sample.valueOf(part, samples, entry),
                // Every part can come off, unlike the bottom bar's. An empty bar is a screen with
                // no way out; an empty line is a one-line row, which is a legitimate answer.
                onToggle = { write(RowDetails.toggled(parts, part)) },
                onUp = null,
                onDown = null,
            )
        }
    }
}

/** The views the preview offers: the three list steps. Grids have no row to show. */
private val PREVIEW_STEPS = listOf(ViewStep.COMPACT_LIST, ViewStep.LIST, ViewStep.COMFORTABLE)

@Composable
private fun Preview(
    entry: EntryDisplay,
    samples: List<Sample>,
    shown: ViewStep,
    yours: ViewStep,
    onPick: (ViewStep) -> Unit,
    onUseComfortable: () -> Unit,
) {
    val colors = Filet.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.lineSoft, shape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preview", fontSize = 10.sp, color = colors.fg3, modifier = Modifier.weight(1f))
            val mini = RoundedCornerShape(6.dp)
            Row(Modifier.clip(mini).border(1.dp, colors.lineSoft, mini)) {
                for (step in PREVIEW_STEPS) {
                    val on = step == shown
                    Text(
                        step.label.removeSuffix(" list") + if (step == yours) " ✓" else "",
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
                        modifier = Modifier
                            .background(if (on) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { onPick(step) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = colors.lineSoft)

        // Sample folders hold what they say they hold, and their totals arrive settled. See the
        // class comment for why both come in from outside the row.
        val inside = remember(samples) { samples.associate { it.node.path to it.inside } }
        val totals = remember(samples) { SampleTotals(samples.associate { it.node.path to it.total }) }
        CompositionLocalProvider(
            LocalSampleInside provides { node -> inside[node.path] },
            LocalFolderBytes provides totals,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val metrics = PaneMetrics.forWidth(maxWidth.value.toInt(), shown)
                Column {
                    for (s in samples) {
                        FileRow(
                            node = s.node,
                            metrics = metrics,
                            selected = false,
                            dropTarget = false,
                            thumbnails = metrics.icon >= 20,
                            display = entry,
                            count = s.count,
                            onClick = {},
                            onLongClick = {},
                        )
                    }
                }
            }
        }

        if (!shown.twoLine && entry.parts.isNotEmpty()) {
            HorizontalDivider(color = colors.lineSoft)
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
                Text(
                    "${shown.label} shows one line per row, so the line under the name is hidden" +
                        (if (shown == yours) " in your lists" else "") + ". It shows in Comfortable.",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = colors.warn,
                )
                Text(
                    "Use Comfortable",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = TextStyle(textDecoration = TextDecoration.Underline),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onUseComfortable)
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * One part of the line: a tick box, its name, who it applies to, what it prints, and arrows.
 *
 * The tick box and the name both toggle, because a target the size of an 18dp box is a miss
 * waiting to happen. Arrows only on a part that is on and only where it can move - an arrow at
 * the end of the list that does nothing reads as the list being stuck.
 */
@Composable
private fun PartRow(
    part: DetailPart,
    on: Boolean,
    sample: String,
    onToggle: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
) = HoldHint(part.note, Prefs.K_E_PARTS) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(18.dp)
                .clip(box)
                .background(if (on) colors.accent else Color.Transparent)
                .border(2.dp, if (on) colors.accent else colors.fg3, box)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Icon(FiletIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
            Text(
                part.label,
                fontSize = 13.sp,
                color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
            )
            Text(appliesLabel(part), fontSize = 10.sp, color = colors.fg3)
        }
        Text(
            sample,
            fontSize = 11.sp,
            color = colors.fg3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(Modifier.width(64.dp), horizontalArrangement = Arrangement.End) {
            if (onUp != null) Arrow(FiletIcons.Up, "Earlier", onUp)
            if (onDown != null) Arrow(FiletIcons.Down, "Later", onDown)
        }
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon,
        label,
        tint = Filet.colors.fg2,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(7.dp),
    )
}

/** Which rows a part can print on, from [DetailPart.appliesTo] so it cannot disagree with it. */
private fun appliesLabel(part: DetailPart): String {
    val files = part.appliesTo(false)
    val folders = part.appliesTo(true)
    return when {
        files && folders -> "Files and folders"
        folders -> "Folders"
        else -> "Files"
    }
}

/**
 * One entry in the preview.
 *
 * Five of them, at five ages - today, yesterday, three days, twelve days and six weeks back - so
 * that Relative dates and Time of day each visibly change something. The old preview's single
 * date sat twelve days back, past the six days relative dates cover, so that switch changed
 * nothing in it and read as broken.
 */
private class Sample(
    val node: VNode,
    val count: Int?,
    val total: FolderTotal,
    val inside: Inside?,
) {
    companion object {
        private fun at(now: Long, daysBack: Int, hour: Int, minute: Int): Long {
            if (daysBack == 0) return now - 47L * 60_000L
            return Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, -daysBack)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
            }.timeInMillis
        }

        /** A path on no drive, so nothing that reads drives can mistake a sample for a file. */
        private fun path(name: String) = VPath("sample", "/$name")

        /** A file standing inside a sample folder, drawn as its type, the way a peeked one is. */
        private fun file(name: String) = Thing.File(FileTypes.icon(VNode(path(name), false, 0L, 0L).extension))

        fun rows(now: Long): List<Sample> = listOf(
            Sample(
                VNode(path("Old trips"), isDir = true, size = -1, mtime = at(now, 0, 14, 32)),
                count = 13,
                total = FolderTotal.Settled(4_938_100_000L),
                inside = Inside.Things(listOf(file("Beach.jpg"), file("Ridge.jpg"), file("Falls.mp4")), lone = false),
            ),
            Sample(
                VNode(path("Notes"), isDir = true, size = -1, mtime = at(now, 1, 9, 5)),
                count = 3,
                total = FolderTotal.Settled(2_202_009L),
                inside = Inside.Things(listOf(file("Ideas.txt"), file("Receipt.pdf"), Thing.Folder(full = true)), lone = false),
            ),
            Sample(VNode(path("Trail map.pdf"), isDir = false, size = 685_056L, mtime = at(now, 3, 18, 47)), null, FolderTotal.Unknown, null),
            Sample(VNode(path("site-backup.zip"), isDir = false, size = 197_632L, mtime = at(now, 12, 7, 15)), null, FolderTotal.Unknown, null),
            Sample(VNode(path("Reading list.docx"), isDir = false, size = 36_966L, mtime = at(now, 41, 21, 3)), null, FolderTotal.Unknown, null),
        )

        /** What [part] prints for the first sample it applies to: the value beside its name. */
        fun valueOf(part: DetailPart, samples: List<Sample>, entry: EntryDisplay): String {
            val s = samples.firstOrNull { part.appliesTo(it.node.isDir) } ?: return ""
            return RowDetails.one(
                part = part,
                isDir = s.node.isDir,
                size = s.node.size,
                at = s.node.mtime,
                count = s.count,
                name = s.node.name,
                display = entry,
                total = s.total,
            )
        }
    }
}

/** Settled totals for the sample folders, so the preview's folders never pulse. */
private class SampleTotals(private val totals: Map<VPath, FolderTotal>) : FolderBytes {
    override suspend fun of(path: VPath): FolderTotal = totals[path] ?: FolderTotal.Unknown
    override fun measure(path: VPath) = Unit
}
