package dev.niccc2007.filet.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.ui.dialogs.DlgKv
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The folder rows of the Details sheet: what is inside, how big, and the biggest thing in it.
 *
 * Draws at once from whatever is cached and never waits on the walk; see [SizeView] for the
 * four states and why "Measuring" and "Confirming" are different ones.
 *
 * Reaches the graph through the application rather than the view model, because the view
 * model is outside this change. [FolderMeasure] needs nothing from the screen, so nothing is
 * lost by it - but if the view model grows a `folderMeasure` accessor, this is the line to
 * point at it.
 */
@Composable
fun FolderFacts(node: VNode) {
    val context = LocalContext.current
    val measure = remember { FolderMeasure.of(FiletApp.graphOf(context)) }
    var cached by remember(node.path) { mutableStateOf<CachedTally?>(null) }
    var forced by remember(node.path) { mutableStateOf(false) }
    val walkFlow: StateFlow<WalkState> = remember(node.path, forced) {
        runCatching { measure.walk(node.path, force = forced) }
            .getOrElse { MutableStateFlow(WalkState.Failed(it.message ?: "cannot measure")) }
    }
    val walk by walkFlow.collectAsState()
    LaunchedEffect(node.path) { cached = runCatching { measure.cached(node.path) }.getOrNull() }

    when (val v = FolderSizes.view(cached, walk)) {
        is SizeView.Measuring -> {
            StatusKv("Contains", if (v.soFar.files + v.soFar.folders == 0L) "…" else FolderSizes.contentsLine(v.soFar) + "…", "Measuring…", Tone.BUSY)
            StatusKv("Size", if (v.soFar.bytes == 0L) "…" else "${humanSize(v.soFar.bytes)} so far", null, Tone.BUSY)
        }
        is SizeView.Confirming -> Rows(v.shown.tally, statusFor(v.shown, confirming = true), Tone.BUSY)
        is SizeView.Confirmed -> Rows(v.tally, "Confirmed", Tone.OK)
        is SizeView.NotMeasured -> {
            if (v.shown != null) Rows(v.shown.tally, statusFor(v.shown, confirming = false), Tone.QUIET)
            MeasureAction { forced = true }
        }
        is SizeView.Unconfirmed -> {
            if (v.shown != null) Rows(v.shown.tally, "Not confirmed: ${v.reason}", Tone.QUIET)
            else DlgKv("Size", "could not measure: ${v.reason}")
        }
    }
}

private enum class Tone { BUSY, OK, QUIET }

private fun statusFor(c: CachedTally, confirming: Boolean): String {
    val source = when (c.source) {
        TallySource.INDEX -> if (c.at > 0) "Last indexed ${ago(c.at)}" else "From the index"
        TallySource.WALK -> "Measured ${ago(c.at)}"
    }
    return if (confirming) "$source · Confirming…" else source
}

@Composable
private fun Rows(t: FolderTally, status: String, tone: Tone) {
    val floor = if (t.partial) "at least " else ""
    StatusKv("Contains", floor + FolderSizes.contentsLine(t), status, tone)
    DlgKv("Directly", FolderSizes.directLine(t))
    DlgKv("Size", "$floor${humanSize(t.bytes)}  (${FolderSizes.grouped(t.bytes)} bytes)")
    // Pre-empts the report that Filet and the system Storage screen disagree: they measure
    // different things, and both are right.
    Text(
        "Sum of file sizes; system Storage counts disk blocks.",
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        color = Filet.colors.fg3,
        modifier = Modifier.padding(start = 110.dp, bottom = 4.dp),
    )
    if (t.partial) DlgKv("Unreadable", FolderSizes.plural(t.unreadable.toLong(), "folder"))
    t.largestName?.takeIf { t.largestBytes > 0 }?.let {
        DlgKv("Largest", "$it${if (t.largestIsDir) "/" else ""}  ·  ${humanSize(t.largestBytes)}")
    }
}

/** A [DlgKv] with a state line under the value. Same column widths, so the sheet stays aligned. */
@Composable
private fun StatusKv(key: String, value: String, status: String?, tone: Tone) {
    val colors = Filet.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, fontSize = 12.sp, color = colors.fg3, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            if (status != null) {
                Text(
                    status,
                    fontSize = 10.5.sp,
                    color = when (tone) {
                        Tone.BUSY -> colors.accent
                        Tone.OK -> colors.good
                        Tone.QUIET -> colors.fg3
                    },
                )
            }
        }
    }
}

@Composable
private fun MeasureAction(onClick: () -> Unit) {
    Text(
        // Only remote folders get here: walking one is a network round trip per folder, so it
        // happens when asked and not because a sheet was opened.
        "Measure now (reads every folder over the network)",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 110.dp, top = 2.dp).clickable(onClick = onClick).padding(vertical = 3.dp),
    )
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}
