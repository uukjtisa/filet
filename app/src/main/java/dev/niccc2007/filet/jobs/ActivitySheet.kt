package dev.niccc2007.filet.jobs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.ListNameText
import dev.niccc2007.filet.ui.LocalPaneQuery
import dev.niccc2007.filet.ui.NoMatch
import dev.niccc2007.filet.ui.TextMatch
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.highlighted
import dev.niccc2007.filet.ui.tabs.DayLabel
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.theme.Filet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** On an intent from a job's notification: open the Activity tab. Read by MainActivity. */
const val EXTRA_OPEN_ACTIVITY = "dev.niccc2007.filet.extra.OPEN_ACTIVITY"

/** What the Activity tab can ask the rest of the app to do with a job's result. */
class ActivityActions(
    /** A tap on a finished job: a folder opens, a file is shown in its folder. */
    val go: (String) -> Unit,
    /** Open the result itself: the folder, or the file in whatever opens it. */
    val open: (String) -> Unit,
    val showInFolder: (String) -> Unit,
    val copy: (String) -> Unit,
)

/**
 * "What is this phone doing, and what did it do while I was not looking." A tab, and the only
 * surface for it.
 *
 * ## Why one tab and no sheet
 *
 * There were two: a bottom sheet the bar's Activity button opened, and this tab. They drew the
 * same ledger with different rows - the sheet had no sections and no way to open a result - so
 * which of the two somebody met decided what they could do with a finished copy. One surface,
 * reached from the bar, the side bar and a job's notification alike.
 *
 * ## The sections
 *
 * Running and Waiting answer "can I put the phone down yet". Failed and Finished answer "did the
 * thing I started actually work", and are grouped by day because that question is asked about
 * yesterday as often as about now - history survives a restart (see [JobLedger]). Failed is
 * above Finished: a failure is the row somebody has to act on.
 *
 * ## What a row does
 *
 * - **Tap** a running job: it opens to show from and to, the count, the data moved, the speed and
 *   the time left, with Pause and Stop. Tap a finished one: it goes to what it made.
 * - **Hold**: everything else - open the result, show it in its folder, retry, run again, copy
 *   the details, remove it.
 * - **Swipe** a finished row away to clear it.
 */
@Composable
fun ActivityBody(ledger: JobLedger, actions: ActivityActions) {
    val jobs by ledger.jobs.collectAsState()
    val query = LocalPaneQuery.current
    val shown = remember(jobs, query) {
        jobs.filter { TextMatch.matches(query, it.title, it.subtitle, it.detail, it.source, it.error, it.from, it.to) }
    }
    val running = shown.filter { it.state == JobState.RUNNING }
    val waiting = shown.filter { it.state == JobState.WAITING }
    val failed = shown.filter { it.state == JobState.FAILED }
    val finished = shown.filter { it.state == JobState.DONE || it.state == JobState.CANCELLED }
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var menuFor by remember { mutableStateOf<Job?>(null) }

    // The time left and the day headings move on their own; one clock for the whole list.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val toggle: (Job) -> Unit = { j ->
        expanded = if (j.id in expanded) expanded - j.id else expanded + j.id
    }
    val tap: (Job) -> Unit = { j ->
        if (!j.running && j.opened.isNotEmpty()) actions.go(j.opened) else toggle(j)
    }

    LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("activity")) {
        item {
            TabHeader("Activity") {
                TabButton("Clear finished", FiletIcons.Delete, enabled = jobs.any { !it.running }) {
                    ledger.clearFinished()
                }
            }
        }

        if (jobs.isEmpty()) {
            item { EmptyTab(FiletIcons.Jobs, "Nothing running, nothing recent") }
        } else if (shown.isEmpty()) {
            item { NoMatch(query) }
        }

        fun section(title: String, list: List<Job>, swipe: Boolean) {
            if (list.isEmpty()) return
            item(key = "h-$title") { SectionRow(title) }
            items(list, key = { "j-" + it.id }) { job ->
                JobRow(
                    job, now, query,
                    expanded = job.id in expanded,
                    onTap = { tap(job) },
                    onHold = { menuFor = job },
                    onStop = { ledger.requestCancel(job.id) },
                    onPause = { if (job.paused) ledger.resume(job.id) else ledger.pause(job.id) },
                    onSwipedAway = if (swipe) ({ ledger.remove(job.id) }) else null,
                )
            }
        }

        fun byDay(title: String, list: List<Job>) {
            if (list.isEmpty()) return
            item(key = "h-$title") { SectionRow(title) }
            for ((day, group) in JobHistory.byDay(list, now)) {
                item(key = "d-$title-$day") { DayLabel(day) }
                items(group, key = { "j-" + it.id }) { job ->
                    JobRow(
                        job, now, query,
                        expanded = job.id in expanded,
                        onTap = { tap(job) },
                        onHold = { menuFor = job },
                        onStop = {},
                        onPause = {},
                        onSwipedAway = { ledger.remove(job.id) },
                    )
                }
            }
        }

        section("Running", running, swipe = false)
        section("Waiting", waiting, swipe = false)
        byDay("Failed", failed)
        byDay("Finished", finished)
        item { Spacer(Modifier.height(14.dp)) }
    }

    menuFor?.let { job ->
        // Read fresh, so a job that finished while its menu was open offers what it can do now.
        val live = jobs.firstOrNull { it.id == job.id } ?: job
        JobMenu(live, now, ledger, actions) { menuFor = null }
    }
}

/**
 * One job.
 *
 * **Stop** stays on the collapsed row while it runs, as it always was: the row somebody is
 * looking at while the work runs is the one where calling it off matters most. Pause and the
 * figures are one tap further, because they are looked at, not reached for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JobRow(
    job: Job,
    now: Long,
    query: String,
    expanded: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onSwipedAway: (() -> Unit)?,
) {
    val colors = Filet.colors
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(1) }
    val swipe = if (onSwipedAway != null && !job.running) {
        Modifier.draggable(
            rememberDraggableState { d -> scope.launch { offset.snapTo(offset.value + d) } },
            Orientation.Horizontal,
            onDragStopped = { v ->
                // Away past 40% of the row or on a fling; anything less springs back, so a
                // sideways wobble while scrolling never clears a row.
                if (abs(offset.value) > width * 0.4f || abs(v) > 2_000f) {
                    offset.animateTo(if (offset.value + v / 10f < 0) -width.toFloat() else width.toFloat())
                    onSwipedAway()
                } else {
                    offset.animateTo(0f)
                }
            },
        )
    } else {
        Modifier
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .then(swipe)
            .combinedClickable(onClick = onTap, onLongClick = onHold)
            .animateContentSize()
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                iconOf(job), null,
                tint = when (job.state) {
                    JobState.FAILED -> colors.bad
                    JobState.DONE -> colors.good
                    JobState.CANCELLED, JobState.WAITING -> colors.fg3
                    JobState.RUNNING -> colors.accent
                },
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                ListNameText(
                    highlighted(job.title, query, colors.accent),
                    MaterialTheme.colorScheme.onSurface,
                    startSp = 12.5f,
                    lines = 2,
                )
                Text(
                    subline(job),
                    fontSize = 10.sp,
                    color = if (job.state == JobState.FAILED) colors.bad else colors.fg3,
                    maxLines = if (expanded) 4 else 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stateWord(job),
                fontSize = 10.sp,
                color = if (job.state == JobState.DONE) colors.good else colors.fg3,
            )
            if (job.running && job.cancellable) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    FiletIcons.Close, "Stop " + job.title,
                    tint = colors.bad,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onStop)
                        .padding(8.dp),
                )
            } else if (!job.running && job.opened.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(FiletIcons.FolderOpen, null, tint = colors.fg3, modifier = Modifier.size(14.dp))
            }
        }
        if (job.running) {
            Spacer(Modifier.height(5.dp))
            // Indeterminate when the total is genuinely unknown, rather than inventing a
            // percentage that then jumps backwards.
            val p = job.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(3.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            }
        }
        if (expanded) JobFigures(job, now, onPause, onStop)
    }
}

/** The opened row: where from, where to, how much, how fast, how long, and the two controls. */
@Composable
private fun JobFigures(job: Job, now: Long, onPause: () -> Unit, onStop: () -> Unit) {
    val colors = Filet.colors
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp)) {
        @Composable
        fun line(label: String, value: String?) {
            if (value.isNullOrEmpty()) return
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(label, fontSize = 10.5.sp, color = colors.fg3, modifier = Modifier.width(54.dp))
                Text(value, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        line("From", job.from.ifEmpty { null }?.let(::readablePath))
        line("To", job.to.ifEmpty { null }?.let(::readablePath))
        line("Items", JobText.items(job))
        line("Data", JobText.amount(job))
        if (job.running) {
            line("Speed", job.bytesPerSec?.takeIf { it > 1.0 }?.let(JobText::rate))
            line("Left", if (job.paused) "paused" else JobRate.etaMs(job, now)?.let(JobText::eta))
        }
        if (job.detail.isNotEmpty() && !job.running) line("Result", job.detail)
        job.error?.let { line("Error", it) }
        if (job.running && (job.pausable || job.cancellable)) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.pausable) Chip(if (job.paused) FiletIcons.Play else FiletIcons.Pause, if (job.paused) "Resume" else "Pause", onPause)
                if (job.cancellable) Chip(FiletIcons.Close, "Stop", onStop)
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** Everything a job can have done to it, on a hold. Only what applies to this one is offered. */
@Composable
private fun JobMenu(job: Job, now: Long, ledger: JobLedger, actions: ActivityActions, onClose: () -> Unit) {
    Dlg(onDismiss = onClose) {
        DlgHeader(
            icon = iconOf(job),
            title = job.title,
            sub = subline(job),
            tone = if (job.state == JobState.FAILED) DlgTone.BAD else DlgTone.NORMAL,
            onClose = onClose,
        )
        DlgBody {
            val result = job.opened.takeIf { it.isNotEmpty() && !job.running }
            if (result != null) {
                DlgPick(FiletIcons.Open, "Open result") { onClose(); actions.open(result) }
                DlgPick(FiletIcons.FolderOpen, "Show in folder") { onClose(); actions.showInFolder(result) }
            }
            if (job.running && job.pausable) {
                DlgPick(if (job.paused) FiletIcons.Play else FiletIcons.Pause, if (job.paused) "Resume" else "Pause") {
                    onClose()
                    if (job.paused) ledger.resume(job.id) else ledger.pause(job.id)
                }
            }
            if (job.running && job.cancellable) {
                DlgPick(FiletIcons.Close, "Stop") { onClose(); ledger.requestCancel(job.id) }
            }
            if (!job.running && job.canRunAgain) {
                val retry = job.state == JobState.FAILED || job.state == JobState.CANCELLED
                DlgPick(FiletIcons.Refresh, if (retry) "Retry" else "Run again") { onClose(); ledger.runAgain(job.id) }
            }
            DlgPick(FiletIcons.Copy, "Copy details") { onClose(); actions.copy(JobText.details(job, now)) }
            if (!job.running) {
                DlgPick(FiletIcons.Delete, "Remove") { onClose(); ledger.remove(job.id) }
            }
        }
    }
}

private fun subline(job: Job): String = buildString {
    append(job.source)
    if (job.subtitle.isNotEmpty()) append(" · ").append(job.subtitle)
    if (job.paused) append(" · paused")
    if (job.detail.isNotEmpty()) append(" · ").append(job.detail)
    job.error?.let { append(" · ").append(it) }
}

private fun stateWord(job: Job): String = when (job.state) {
    JobState.RUNNING -> if (job.paused) "paused" else job.progress?.let { "${(it * 100).toInt()}%" } ?: ""
    JobState.WAITING -> "waiting"
    JobState.DONE -> "done"
    JobState.FAILED -> "failed"
    JobState.CANCELLED -> "stopped"
}

/** A VFS path string as a person reads it: the path part, without the scheme. */
private fun readablePath(raw: String): String =
    runCatching { dev.niccc2007.filet.vfs.VPath.parse(raw).path.ifEmpty { raw } }.getOrDefault(raw)

private fun iconOf(job: Job): ImageVector = when (job.kind) {
    JobKind.COPY -> FiletIcons.Copy
    JobKind.MOVE -> FiletIcons.Move
    JobKind.DELETE -> FiletIcons.Delete
    JobKind.COMPRESS, JobKind.EXTRACT -> FiletIcons.Zip
    JobKind.SAVE -> FiletIcons.Save
    JobKind.INDEX -> FiletIcons.Search
    JobKind.MEASURE -> FiletIcons.Storage
    JobKind.BEAT -> FiletIcons.Audio
    JobKind.SCRIPT -> FiletIcons.Script
    JobKind.APK -> FiletIcons.Apk
    JobKind.FETCH, JobKind.DOWNLOAD -> FiletIcons.Download
    JobKind.OTHER -> FiletIcons.Jobs
}
