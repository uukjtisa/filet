package dev.niccc2007.filet.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.VolumeInfo
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.data.DriveNames
import dev.niccc2007.filet.data.DriveRegistry
import dev.niccc2007.filet.jobs.Job
import dev.niccc2007.filet.jobs.JobKind
import dev.niccc2007.filet.jobs.JobState
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VPath

/**
 * The lines under the storage cards: which remembered drives are not plugged in, and a USB device
 * that is plugged in with nothing on it readable.
 *
 * A pulled-out drive's card goes, because a card that cannot be opened is a dead end on the
 * screen people open most. What it knew stays, in the registry and the index, and this line is
 * where it is found again - one line for all of them, so three old USB sticks are not three cards.
 */
@Composable
internal fun DriveLines(vm: BrowserViewModel) {
    val known by vm.prefs.knownDrives.collectAsState()
    val plugged by vm.drives.plugged.collectAsState()
    val otg by vm.drives.otg.collectAsState()
    val names by vm.prefs.driveNames.collectAsState()
    val gone = DriveRegistry.gone(known, plugged.map { it.uuid }.toSet())
    var sheet by remember { mutableStateOf(false) }
    if (gone.isNotEmpty()) {
        val first = gone.first()
        DriveLine(
            icon = if (first.kind == DriveRegistry.Kind.USB) FiletIcons.Usb else FiletIcons.Sd,
            lead = "Not plugged in: ",
            names = gone.map { DriveLineText.nameOf(it, names) },
            chevron = true,
        ) { sheet = true }
    }
    // A USB device the phone sees and no drive it can read: on many phones OTG switches itself
    // off after a few minutes idle, and the drive then looks dead rather than switched off.
    if (otg) {
        DriveLine(
            icon = FiletIcons.Usb,
            lead = "A USB device is plugged in, but no drive shows up. Some phones need OTG on in Settings.",
            names = emptyList(),
            chevron = false,
            onClick = null,
        )
    }
    if (sheet && gone.isNotEmpty()) GoneSheet(vm, gone, names) { sheet = false }
}

/** One line: `.gone` in the drives mock - raised, 11dp corners, 9 by 12 padding, 10 apart. */
@Composable
private fun DriveLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    lead: String,
    names: List<String>,
    chevron: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.raised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = colors.fg3, modifier = Modifier.size(15.dp))
        Text(
            buildAnnotatedString {
                append(lead)
                names.forEachIndexed { i, n ->
                    if (i > 0) append(", ")
                    withStyle(SpanStyle(color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)) { append(n) }
                }
            },
            fontSize = 11.5.sp,
            color = colors.fg2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (chevron) Icon(FiletIcons.Chevron, null, tint = colors.fg3, modifier = Modifier.size(14.dp))
    }
}

/**
 * Every remembered drive not plugged in: its size, when it was last seen, how many of its files
 * the index holds, and Forget. Forget asks once, in the row, because it deletes that index.
 */
@Composable
private fun GoneSheet(
    vm: BrowserViewModel,
    gone: List<DriveRegistry.Known>,
    names: Map<String, String>,
    onClose: () -> Unit,
) {
    Dlg(onDismiss = onClose) {
        DlgHeader(FiletIcons.Sd, "Not plugged in", onClose = onClose)
        DlgBody {
            DlgCaption("Each is remembered by its own ID. Plug it back in and it returns with its name and index.")
            for (k in gone) GoneRow(vm, k, DriveLineText.nameOf(k, names))
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Close", onClick = onClose)
        }
    }
}

@Composable
private fun GoneRow(vm: BrowserViewModel, k: DriveRegistry.Known, name: String) {
    val colors = Filet.colors
    var files by remember(k.uuid) { mutableStateOf<Int?>(null) }
    LaunchedEffect(k.uuid) { files = vm.filesIndexed(k.path) }
    var asking by remember(k.uuid) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (k.kind == DriveRegistry.Kind.USB) FiletIcons.Usb else FiletIcons.Sd,
            null,
            tint = colors.fg2,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (asking) "Its name and its index go. Nothing on the drive changes."
                else DriveLineText.goneDetail(k, files),
                fontSize = 11.5.sp,
                color = if (asking) colors.warn else colors.fg3,
            )
        }
        if (asking) {
            DlgBtn("Keep") { asking = false }
            DlgBtn("Forget", kind = dev.niccc2007.filet.ui.dialogs.BtnKind.DANGER) { vm.forgetDrive(k) }
        } else {
            Text(
                "Forget",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { asking = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** The pure parts, kept out of the composables so they can be tested. */
internal object DriveLineText {

    /** The name somebody gave it, else what the system calls it. */
    fun nameOf(k: DriveRegistry.Known, names: Map<String, String>): String =
        names[DriveNames.idOf(VPath.of("local", k.path))]?.takeIf { it.isNotBlank() }
            ?: DriveRegistry.displayName(k.kind, k.name)

    /** "119 GB · last seen 3 days ago · 18,402 files indexed". */
    fun goneDetail(k: DriveRegistry.Known, files: Int?, now: Long = System.currentTimeMillis()): String = buildList {
        k.total?.takeIf { it > 0 }?.let { add(humanSize(it)) }
        ago(k.lastSeen, now).takeIf { it.isNotEmpty() }?.let { add("last seen $it") }
        when {
            files == null -> {}
            files == 0 -> add("not indexed")
            else -> add("%,d %s indexed".format(files, if (files == 1) "file" else "files"))
        }
    }.joinToString(" · ")

    /**
     * The jobs writing onto this drive now. A job's destination is a VFS path; on an older phone
     * the drive is written through its granted tree, so that counts as the drive too.
     */
    fun writingTo(jobs: List<Job>, v: VolumeInfo): List<Job> {
        val r = v.removable ?: return emptyList()
        val roots = listOfNotNull(v.node.path.toString(), r.granted?.toString()).map { it.trimEnd('/') }
        return jobs.filter { j ->
            (j.state == JobState.RUNNING || j.state == JobState.WAITING) && j.kind in WRITES &&
                roots.any { root -> j.to == root || j.to.startsWith("$root/") }
        }
    }

    /** "Copying 3 files · don't unplug": the caption while [jobs] write to a drive. */
    fun writingCaption(jobs: List<Job>): String {
        val n = jobs.sumOf { (it.totalItems ?: 1) - (it.doneItems ?: 0) }.coerceAtLeast(1)
        val verb = if (jobs.all { it.kind == JobKind.COPY }) "Copying" else "Writing"
        return "$verb $n ${if (n == 1) "file" else "files"} · don't unplug"
    }

    /** The kinds of work that put files onto a drive. */
    private val WRITES = setOf(
        JobKind.COPY, JobKind.MOVE, JobKind.EXTRACT, JobKind.COMPRESS, JobKind.SAVE,
        JobKind.DOWNLOAD, JobKind.RECEIVED, JobKind.FETCH, JobKind.DELETE,
    )
}
