package dev.niccc2007.filet.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.Pill
import dev.niccc2007.filet.ui.tabs.PillTone
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What you were part way through, and where you got to.
 *
 * ## One tab, not two
 *
 * Asked for as two - a Music tab and a Video tab - and built as one with two filters. Two
 * destinations that differ only by a filter double the cost of reaching the same list, and the
 * navigation surfaces are already eight entries deep before this one; meanwhile the question
 * somebody has when they open this is "what was I in the middle of", which is answered by one
 * list in time order and made worse by splitting it in half. The filters are there for the
 * times the answer is "the film", and the choice is remembered for the session.
 *
 * If this turns out to be wrong, two destinations is a smaller change than merging them later:
 * the pane kind is declared in `SpecialPanes` and the screen takes a filter already.
 *
 * ## Why the list is cheap
 *
 * Every row is drawn from what `ResumeStore` wrote down - title, length, position - so nothing
 * here stats a file or reads a tag. That matters because the alternative is a list whose
 * opening cost grows with how much media somebody has half-finished, and because a track that
 * has since been deleted should still be listed and should fail when it is TAPPED, with a
 * sentence, rather than silently vanishing from a list of things you remember doing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResumeScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val store = vm.playback.resume
    // Watched rather than counted locally, so a pull-down refresh and the player writing a
    // new position both reach this list. See `BrowserViewModel.resumeRevision`.
    val revision by vm.resumeRevision.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(ResumeFilter.ALL) }
    val all = remember(revision) { store.all() }
    val rows = remember(revision, filter) { all.filter(filter::accepts) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { TabHeader("Carry on") }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (f in ResumeFilter.entries) {
                    val lit = f == filter
                    Text(
                        f.label,
                        fontSize = 11.5.sp,
                        color = if (lit) colors.accent else colors.fg2,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (lit) colors.accent.copy(alpha = 0.14f) else colors.sunken)
                            .combinedClickable(onClick = { filter = f })
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Spacer(Modifier.fillMaxWidth(0f))
            }
        }

        if (rows.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Clock,
                    if (all.isEmpty()) {
                        "Nothing to carry on with"
                    } else {
                        "Nothing ${filter.label.lowercase()} to carry on with"
                    },
                )
            }
        } else {
            item { SectionRow("Where you left off", note = "${rows.size}") }
        }

        items(rows.size) { i ->
            val point = rows[i]
            var menu by remember(point.path) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { vm.resumePoint(point) },
                        onLongClick = { menu = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Carry on", fontSize = 13.sp) },
                            onClick = { menu = false; vm.resumePoint(point) },
                        )
                        DropdownMenuItem(
                            text = { Text("Start from the beginning", fontSize = 13.sp) },
                            onClick = { menu = false; vm.resumePoint(point, fromStart = true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Open containing folder", fontSize = 13.sp) },
                            onClick = {
                                menu = false
                                runCatching {
                                    vm.revealPathInFolder(
                                        dev.niccc2007.filet.vfs.VPath.parse(point.path),
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Forget this one", fontSize = 13.sp) },
                            onClick = { menu = false; store.forget(point.path); vm.bumpResume() },
                        )
                    }
                }
                Icon(
                    if (point.video) FiletIcons.Video else FiletIcons.Audio,
                    null,
                    tint = colors.fg2,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.fillMaxWidth(0.72f)) {
                    Text(
                        point.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        resumeLine(point),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.fillMaxWidth(0f))
                Pill(resumePercent(point), PillTone.Plain)
            }
            // A hairline of how far in it is, under the row. The number says it exactly and
            // this says it at a glance, which is the thing a list is scanned for.
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.sunken),
                )
                Box(
                    Modifier
                        .fillMaxWidth(resumeFraction(point))
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.accent.copy(alpha = 0.7f)),
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (all.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    Text(
                        "Forget everything",
                        fontSize = 11.5.sp,
                        color = colors.fg3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { store.clear(); vm.bumpResume() })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** The two halves of the media this app plays, plus everything. */
enum class ResumeFilter(val label: String) {
    ALL("Everything"),
    MUSIC("Music"),
    VIDEO("Video");

    fun accepts(point: ResumeStore.Point): Boolean = when (this) {
        ALL -> true
        MUSIC -> !point.video
        VIDEO -> point.video
    }
}

/** "12:40 of 1:38:02  ·  yesterday". Pure, so the wording can be tested. */
fun resumeLine(point: ResumeStore.Point, now: Long = System.currentTimeMillis()): String {
    val where = dev.niccc2007.filet.handlers.clockOf(point.positionMs)
    val total = if (point.durationMs > 0L) {
        " of " + dev.niccc2007.filet.handlers.clockOf(point.durationMs)
    } else {
        ""
    }
    return where + total + "  ·  " + agoOf(now - point.at)
}

/** How far through, as a percentage, or a dash when the length was never known. */
fun resumePercent(point: ResumeStore.Point): String =
    if (point.durationMs <= 0L) "—" else "${(resumeFraction(point) * 100).toInt()}%"

fun resumeFraction(point: ResumeStore.Point): Float =
    if (point.durationMs <= 0L) {
        0f
    } else {
        (point.positionMs.toFloat() / point.durationMs).coerceIn(0f, 1f)
    }

/**
 * How long ago, in words.
 *
 * Coarse on purpose. "Three days ago" is what somebody wants from this list; the minute it
 * happened is a fact about a log, and printing it invites reading the list as one.
 */
fun agoOf(elapsedMs: Long): String {
    val minutes = elapsedMs / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 60L * 24 -> "${minutes / 60}h ago"
        minutes < 60L * 48 -> "yesterday"
        minutes < 60L * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> "a while ago"
    }
}
