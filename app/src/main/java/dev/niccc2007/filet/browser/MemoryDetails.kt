package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.index.MemoryEvent
import dev.niccc2007.filet.index.MemoryEventKind
import dev.niccc2007.filet.index.MemoryView
import dev.niccc2007.filet.index.PhoneSince
import dev.niccc2007.filet.index.PhoneSinceRule
import dev.niccc2007.filet.ui.dialogs.DlgKv
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.provider.MediaDates
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Filet's memory of an item, in the Details sheet and nowhere else.
 *
 * Only here, deliberately: every list, row and sort in the app shows what the SYSTEM says, so
 * Filet's memory can never make a listing disagree with every other app on the phone. This is
 * where somebody goes to ask "how long has this really been here, and what happened to it".
 *
 * "On this phone since" and "Taken" are two rows and are worded apart on purpose. One is when
 * the file arrived here; the other is when the photo was made, possibly years earlier on
 * somebody else's phone. Folding them together would make a picture sent yesterday read as
 * having been here since 2019.
 */
@Composable
fun MemoryFacts(node: VNode) {
    val context = LocalContext.current
    val graph = remember { FiletApp.graphOf(context) }
    // PLAN.md R1: off until it has been driven on a device. `adb shell` can flip `details.memory`.
    // The switch lives here, not in Settings: this is the only place the rows exist, and a
    // hidden detail is best revealed where it is read.
    val enabled by graph.prefs.memoryDetails.collectAsState()
    if (!enabled) {
        Text(
            "Show Filet's memory",
            fontSize = 11.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 6.dp)
                .clickable { graph.prefs.setMemoryDetails(true) }
                .padding(vertical = 3.dp),
        )
        return
    }
    var memory by remember(node.path) { mutableStateOf<MemoryView?>(null) }
    var dates by remember(node.path) { mutableStateOf<MediaDates.Dates?>(null) }
    var folderAdded by remember(node.path) { mutableStateOf<Long?>(null) }
    var loaded by remember(node.path) { mutableStateOf(false) }

    LaunchedEffect(node.path) {
        memory = runCatching { graph.index.memoryOf(node.path) }.getOrNull()
        val os = runCatching { graph.vfs.osPath(node.path) }.getOrNull()
        if (os != null) {
            if (node.isDir) folderAdded = MediaDates.earliestUnder(context, os)
            else dates = MediaDates.ofFile(context, os)
        }
        loaded = true
    }
    if (!loaded) return

    val own = PhoneSinceRule.of(memory?.own, if (node.isDir) folderAdded else dates?.added)
    val since = if (node.isDir) {
        val inside = memory?.earliestInside?.let { (at, found) ->
            if (found) PhoneSince.Before(at) else PhoneSince.At(at, bySystem = false)
        } ?: PhoneSince.NotYetSeen
        PhoneSinceRule.merge(own, inside)
    } else own
    DlgKv("On this phone since", sinceText(since))
    dates?.taken?.let { DlgKv("Taken", DAY.format(Date(it))) }

    val events = memory?.own?.events.orEmpty()
    if (events.isNotEmpty()) {
        DlgSection("History")
        for (e in events) DlgKv(STAMP.format(Date(e.at)), eventText(e))
        Text(
            "Recorded by Filet from its own scans and moves.",
            fontSize = 10.5.sp,
            color = Filet.colors.fg3,
            modifier = Modifier.padding(start = 110.dp, bottom = 4.dp),
        )
    }
}

internal fun sinceText(s: PhoneSince): String = when (s) {
    is PhoneSince.At -> DAY.format(Date(s.at)) + if (s.bySystem) "  ·  system record" else "  ·  Filet saw it arrive"
    is PhoneSince.Before -> "before " + DAY.format(Date(s.at)) + "  ·  already here when Filet first looked"
    PhoneSince.NotYetSeen -> "not recorded yet"
}

internal fun eventText(e: MemoryEvent): String = when (e.kind) {
    MemoryEventKind.RENAMED -> "Renamed from ${nameOf(e.fromPath)}"
    MemoryEventKind.MOVED -> "Moved from ${folderOf(e.fromPath)}"
    MemoryEventKind.RESIZED -> "Size ${humanSize(e.oldSize ?: 0)} → ${humanSize(e.newSize ?: 0)}"
    MemoryEventKind.GONE -> "Gone from ${e.fromPath.orEmpty()}"
}

private fun nameOf(vpath: String?): String = vpath?.trimEnd('/')?.substringAfterLast('/').orEmpty()

private fun folderOf(vpath: String?): String {
    val p = vpath?.substringAfter("://", vpath).orEmpty().trimEnd('/')
    return p.substringBeforeLast('/', "/").ifEmpty { "/" }
}

private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
