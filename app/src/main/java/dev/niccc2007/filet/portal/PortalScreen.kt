package dev.niccc2007.filet.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.link.PortalLink
import dev.niccc2007.filet.settings.SmallButton
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.tabs.ChipRow
import dev.niccc2007.filet.ui.tabs.NLead
import dev.niccc2007.filet.ui.tabs.NRow
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.tabs.ToggleRow
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import java.text.DateFormat
import java.util.Date

/**
 * The Portal page: how long files are kept, which hosts are on, and every send with its link.
 * Send files picks what to send; the Share dialog's Filet Portal does the same for a selection.
 */
@Composable
fun PortalScreen(vm: BrowserViewModel) {
    val portal = vm.portal
    val hosts by portal.hosts.collectAsState()
    val keep by portal.keep.collectAsState()
    val scatter by portal.scatter.collectAsState()
    val sends by portal.sends.collectAsState()
    val colors = Filet.colors
    val now = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxSize(), state = dev.niccc2007.filet.browser.rememberKeptListState("portal")) {
        item { TabHeader("Filet Portal") { SmallButton("Send files") { vm.pickToPortal() } } }
        item {
            Text(
                "Files are sealed on this phone, then spread over free hosts. Only the link opens them.",
                fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 4.dp),
            )
        }
        item {
            Box(Modifier.padding(horizontal = 15.dp)) {
                ChipRow("Keep files for", Portal.KEEPS.map { Portal.keepLabel(it) }, Portal.keepLabel(keep)) { picked ->
                    Portal.KEEPS.firstOrNull { Portal.keepLabel(it) == picked }?.let { portal.setKeep(it) }
                }
            }
        }
        item { SectionRow("Hosts") }
        items(PortalHost.entries) { h ->
            val on = h in hosts
            NRow(
                title = h.label,
                sub = "up to ${humanSize(h.maxBytes)} · keeps ${keeps(h)}" +
                    if (on && !h.keeps(keep)) " · not for ${Portal.keepLabel(keep)}" else "",
                lead = NLead.Glyph(FiletIcons.Share),
                trailing = {
                    Switch(checked = on, onCheckedChange = { portal.setHost(h, it) }, modifier = Modifier.scale(0.7f))
                },
                onClick = { portal.setHost(h, !on) },
            )
        }
        item {
            Column(Modifier.padding(horizontal = 15.dp)) {
                ToggleRow("Scatter: split every file across the hosts", scatter) { portal.setScatter(it) }
                Text(
                    "No host then holds a whole file, even sealed. More pieces, so it is slower.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                )
            }
        }
        item { SectionRow("Sent (${sends.size})") }
        if (sends.isEmpty()) {
            item {
                Text(
                    "Nothing sent yet. Send files above, or select files and pick Share > Filet Portal.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                    modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 4.dp),
                )
            }
        }
        items(sends, key = { it.id }) { s ->
            NRow(
                title = sendTitle(s),
                sub = sendLine(s, now),
                lead = NLead.Glyph(if (s.error.isNotEmpty() || s.failed.isNotEmpty()) FiletIcons.Info else FiletIcons.Share),
                trailing = { if (!s.running) SmallBtn("Forget") { portal.forget(s.id) } },
                onClick = { vm.openPortalSend(s.id) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun keeps(h: PortalHost): String =
    if (h.keepsUpTo >= DAY) "up to ${h.keepsUpTo / DAY} days" else if (h.keepsUpTo > HOUR) "${h.keepsUpTo / HOUR} hours" else "1 hour"

private fun sendTitle(s: PortalSend): String =
    s.files.singleOrNull()?.name ?: "${s.files.size} files · ${humanSize(s.total)}"

/** A time as the phone writes it, with the day when it is not today. */
internal fun until(at: Long, now: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    val day = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) } }
    return if (day(at) == day(now)) time
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)) + " " + time
}

private fun sendLine(s: PortalSend, now: Long): String = when {
    s.running -> "sending · ${humanSize(s.doneBytes)} of ${humanSize(s.total)}"
    s.error.isNotEmpty() -> s.error
    s.expires in 1..now -> "expired"
    s.failed.isNotEmpty() -> "${s.failed.size} did not go · until ${until(s.expires, now)}"
    else -> "until ${until(s.expires, now)} · tap for the QR"
}

/** A send: its progress while it runs, then the QR and link, and how each file went. */
@Composable
fun PortalSendSheet(vm: BrowserViewModel, id: Long, onClose: () -> Unit) {
    val sends by vm.portal.sends.collectAsState()
    val s = sends.firstOrNull { it.id == id } ?: run { onClose(); return }
    val colors = Filet.colors
    val now = System.currentTimeMillis()
    Dlg(onClose) {
        DlgHeader(
            FiletIcons.Share, "Filet Portal",
            sub = sendTitle(s) + if (s.link.isNotEmpty()) " · until ${until(s.expires, now)}" else "",
            onClose = onClose,
        )
        DlgBody {
            when {
                s.running -> {
                    Text("Sealing and sending…", fontSize = 12.sp, color = colors.fg2)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) (s.doneBytes.toFloat() / s.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    DlgCaption("${humanSize(s.doneBytes)} of ${humanSize(s.total)}. It carries on if you close this.")
                }
                s.error.isNotEmpty() -> DlgWarn(s.error)
                else -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        dev.niccc2007.filet.qr.FiletQr(s.link, Modifier.size(196.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.link, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                        textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    DlgCaption("Anyone with this QR or link can download these. A phone with Filet opens it in Filet.")
                }
            }
            DlgSection("Files")
            for (f in s.files.take(20)) {
                DlgPick(
                    FileKind.of(VNode(VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0)).icon,
                    f.name,
                    detail = when {
                        f.failed -> "did not go: no host took it"
                        f.hosts.isNotEmpty() -> humanSize(f.size) + " · " + f.hosts.joinToString()
                        else -> humanSize(f.size)
                    },
                ) {}
            }
            if (s.files.size > 20) DlgCaption("and ${s.files.size - 20} more")
        }
        DlgFooter {
            if (!s.running && (s.failed.isNotEmpty() || s.error.isNotEmpty())) {
                DlgBtn("Send again", fill = false) {
                    vm.portal.resend(s, all = s.error.isNotEmpty())
                    onClose()
                }
            }
            if (s.link.isNotEmpty()) DlgBtn("Copy link") { vm.copyText(s.link, "Link copied") }
            DlgBtn("Done", BtnKind.PRIMARY, onClick = onClose)
        }
    }
}

/** A Portal link opened in Filet: what it holds, where to save it, and Download. */
@Composable
fun PortalReceiveSheet(vm: BrowserViewModel, link: PortalLink, onClose: () -> Unit) {
    val portal = vm.portal
    var manifest by remember(link) { mutableStateOf<PortalManifest?>(null) }
    var problem by remember(link) { mutableStateOf<String?>(null) }
    val into by vm.portalInto.collectAsState()
    LaunchedEffect(link) {
        runCatching { portal.look(link) }
            .onSuccess { manifest = it }
            .onFailure { problem = it.message ?: "These files could not be opened." }
    }
    val colors = Filet.colors
    val m = manifest
    val now = System.currentTimeMillis()
    Dlg(onClose) {
        DlgHeader(
            FiletIcons.Download,
            if (m == null) "Filet Portal" else "Files from ${m.from.ifEmpty { "a Filet" }}",
            sub = when {
                m == null -> "Files sent over the internet"
                m.expires in 1..now -> "via Filet Portal · may have expired"
                else -> "via Filet Portal · until ${until(m.expires, now)}"
            },
            tone = if (problem != null) DlgTone.WARN else DlgTone.NORMAL,
            onClose = onClose,
        )
        DlgBody {
            when {
                problem != null -> DlgWarn(problem!!)
                m == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
                    Text("Opening the list of files…", fontSize = 12.sp, color = colors.fg2)
                }
                else -> {
                    for (f in m.files.take(20)) {
                        DlgPick(
                            FileKind.of(VNode(VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0)).icon,
                            f.name,
                            detail = humanSize(f.size),
                        ) {}
                    }
                    if (m.files.size > 20) DlgCaption("and ${m.files.size - 20} more")
                    DlgSection("Save to")
                    DlgPick(FiletIcons.Folder, "Filet/Received", selected = into == null) { vm.setPortalInto(null) }
                    DlgPick(
                        FiletIcons.Open,
                        into?.let { it.name.ifEmpty { it.toString() } } ?: "Choose folder",
                        detail = if (into == null) "Pick where they land" else "Tap to pick another",
                        selected = into != null,
                    ) { vm.pickPortalFolder() }
                }
            }
        }
        DlgFooter {
            DlgBtn("Not now", onClick = onClose)
            if (m != null) {
                DlgBtn("Download", BtnKind.PRIMARY) {
                    val target = into ?: portal.inbox
                    portal.download(link, m, target)
                    vm.toast("Downloading to " + (into?.name ?: "Filet/Received"))
                    onClose()
                }
            }
        }
    }
}

/** Portal's sheets, wherever Filet is. */
@Composable
fun PortalPopups(vm: BrowserViewModel) {
    val shown by vm.portalShown.collectAsState()
    val receiving by vm.portalReceiving.collectAsState()
    shown?.let { PortalSendSheet(vm, it, vm::closePortalSend) }
    receiving?.let { PortalReceiveSheet(vm, it, vm::closePortalReceive) }
}
