package dev.niccc2007.filet.storage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath

/** What Disk map shows under its strip. All three come from the one walk. */
enum class DiskView(val label: String) {
    FOLDERS("Folders"),
    LARGEST("Largest files"),
    KINDS("By type"),
}

/**
 * The strip along the top of a pane in Disk map: this folder's total and its share of the drive,
 * the drive's own bar, the three views, the walk's progress with Stop, and the way out.
 *
 * Every figure here is the walk's: nothing is asked of the drive while the strip is drawn. The
 * drive's size and free space are the ones the Home card already holds.
 */
@Composable
fun DiskStrip(
    map: DiskMap,
    folder: VPath,
    volume: VolumeInfo?,
    view: DiskView,
    onView: (DiskView) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Filet.colors
    val rev by map.revision.collectAsState()
    val walking by map.walking.collectAsState()
    val here = folder.toString()
    val (bytes, settled, files) = rememberRead(rev, here) { map.read { Triple(it.bytes(here), it.settled(here), it.filesRead) } }
    val filesHere = rememberRead(rev, here) { map.read { it.files(here) } }
    val total = volume?.total
    val used = total?.let { t -> volume.free?.let { t - it } }
    val isRoot = here == map.root.toString()

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                bytes?.let { humanSize(it) + if (!settled) "+" else "" } ?: "…",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            val of = when {
                total == null -> null
                isRoot && used != null -> "${humanSize(used)} of ${humanSize(total)} used"
                else -> DiskOrder.percent(bytes, total).takeIf { it.isNotEmpty() }?.let { p ->
                    "$p of ${volume?.label ?: "the drive"}" + (used?.let { " · ${humanSize(it)} of ${humanSize(total)} used" } ?: "")
                }
            }
            Text(
                of ?: "",
                fontSize = 11.sp,
                color = colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
            Icon(
                FiletIcons.Close,
                "Close Disk map",
                tint = colors.fg3,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(7.dp),
            )
        }
        // The drive: this folder, the rest of what is used, and what is free.
        if (total != null && total > 0) {
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.high),
            ) {
                val mine = DiskOrder.share(bytes, total)
                val rest = (DiskOrder.share(used, total) - mine).coerceAtLeast(0f)
                if (mine > 0f) Box(Modifier.weight(mine).fillMaxHeight().background(colors.accent))
                if (rest > 0f) Box(Modifier.weight(rest).fillMaxHeight().background(colors.fg3))
                val free = (1f - mine - rest).coerceAtLeast(0f)
                if (free > 0f) Spacer(Modifier.weight(free))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in DiskView.entries) {
                val on = v == view
                Text(
                    v.label,
                    fontSize = 11.sp,
                    color = if (on) MaterialTheme.colorScheme.onSurface else colors.fg2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (on) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier.border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp)))
                        .clickable { onView(v) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        // The walk: running with Stop, otherwise Walk again. Always offered once it has
        // finished, because a delete is taken off the map at once but a move or a download made
        // elsewhere is not, and the way to catch up is to read the drive again.
        val note = when {
            // A share is read folder by folder over the network, which is slow and costs the
            // other end something, so the strip says so while it runs.
            walking && volume?.remote == true -> "Reading every folder over the network · ${"%,d".format(files)} files"
            walking -> "Measuring · ${"%,d".format(files)} files read"
            map.stopped -> "Stopped part way. Sizes with + are at least that."
            map.unreadable > 0 -> "${map.unreadable} folder${if (map.unreadable > 1) "s" else ""} could not be read and are not counted."
            else -> "Measured · ${"%,d".format(filesHere)} files here"
        }
        run {
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (walking) {
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.6.dp, color = colors.warn)
                    Spacer(Modifier.width(7.dp))
                }
                Text(note, fontSize = 10.5.sp, color = if (walking) colors.warn else colors.fg3, modifier = Modifier.weight(1f))
                val act = if (walking) "Stop" else "Walk again"
                run {
                    Text(
                        act,
                        fontSize = 11.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (walking) map.stop() else map.walk() }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The share bar under a row: how much of the folder it is in this row takes. Starts where the
 * row's name starts, so it reads as part of the row rather than a rule between rows.
 */
@Composable
fun DiskShareBar(share: Float, percent: String, folder: Boolean, inset: Dp) {
    val colors = Filet.colors
    val shown by animateFloatAsState(share, label = "share")
    Row(
        Modifier.fillMaxWidth().padding(start = inset, end = 14.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.high)) {
            if (shown > 0f) {
                Box(
                    Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(if (folder) colors.accent else colors.fg3),
                )
            }
        }
        Text(percent, fontSize = 9.5.sp, color = colors.fg3, modifier = Modifier.width(34.dp).padding(start = 6.dp))
    }
}

/**
 * The largest files anywhere under [folder], flat. A tap goes to the file's own folder, still in
 * Disk map, where it can be acted on like any row.
 */
@Composable
fun DiskLargest(map: DiskMap, folder: VPath, onGo: (VPath) -> Unit) {
    val colors = Filet.colors
    val rev by map.revision.collectAsState()
    val here = folder.toString()
    val (big, total) = rememberRead(rev, here) { map.read { it.largestUnder(here, 200) to it.bytes(here) } }
    if (big.isEmpty()) {
        Empty(if (map.walking.value) "Reading…" else "No files here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(big, key = { it.path }) { b ->
            val name = b.path.substringAfterLast('/')
            val where = b.path.removePrefix(here).trimStart('/').substringBeforeLast('/', "").ifEmpty { "Here" }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onGo(VPath.parse(b.path)) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Text(where, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.fg3)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(humanSize(b.bytes), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(DiskOrder.percent(b.bytes, total), fontSize = 10.5.sp, color = colors.fg3)
                }
            }
        }
    }
}

/** What kinds of file take the space under [folder], largest first. */
@Composable
fun DiskKinds(map: DiskMap, folder: VPath) {
    val colors = Filet.colors
    val rev by map.revision.collectAsState()
    val here = folder.toString()
    val (kinds, total) = rememberRead(rev, here) { map.read { it.kinds(here) to it.bytes(here) } }
    if (kinds.isEmpty()) {
        Empty(if (map.walking.value) "Reading…" else "No files here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(kinds, key = { it.first.name }) { (kind, bytes) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(kindColour(kind)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.high)) {
                        val share = DiskOrder.share(bytes, total)
                        if (share > 0f) Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(kindColour(kind)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                    Text(humanSize(bytes), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(DiskOrder.percent(bytes, total), fontSize = 10.5.sp, color = colors.fg3)
                }
            }
        }
    }
}

/** One colour per kind, from the theme's own so it follows light and dark. */
@Composable
private fun kindColour(kind: SizeKind): Color {
    val c = Filet.colors
    return when (kind) {
        SizeKind.VIDEOS -> c.warn
        SizeKind.PICTURES -> c.good
        SizeKind.AUDIO -> c.accent
        SizeKind.ARCHIVES -> c.bad
        SizeKind.PACKAGES -> c.accent.copy(alpha = 0.6f)
        SizeKind.DOCUMENTS -> c.fg2
        SizeKind.OTHER -> c.fg3
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, fontSize = 12.sp, color = Filet.colors.fg3)
    }
}

/** A read of the map, again whenever the map's revision or the folder moves. */
@Composable
private fun <T> rememberRead(rev: Int, folder: String, read: () -> T): T = androidx.compose.runtime.remember(rev, folder) { read() }
