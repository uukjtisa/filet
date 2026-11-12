@file:OptIn(ExperimentalLayoutApi::class)

package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.desktop.Autostart
import dev.niccc2007.filet.desktop.Endpoint
import dev.niccc2007.filet.desktop.HostService
import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.Share
import dev.niccc2007.filet.desktop.mount.DriveLetters
import dev.niccc2007.filet.desktop.mount.MountResult
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.mount.WebClientLimit
import dev.niccc2007.filet.webdav.AccessCode
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The one window, as a dashboard: a status strip, then what this PC offers on the left and what is
 * on the network on the right.
 *
 * No menus and no tabs, so everything the tool does is visible at once. A tab would hide one half
 * of a tool whose point is that the two halves are equal; two columns put them side by side with
 * no scrolling between them.
 */
@Composable
fun FiletWindow(host: HostService, peers: StateFlow<List<Peer>>, mounts: MountController, rescan: () -> Unit) {
    val live by peers.collectAsState()
    val known by mounts.known.collectAsState()
    val views by mounts.mounts.collectAsState()
    val swept by mounts.swept.collectAsState()
    // Saved devices stay listed while offline; a share on the network never mounted here follows.
    // Rows are rebuilt from the live list and the OS's drives on every change, so a device that
    // leaves turns its tile offline, and an open mount dialog over it stops offering Mount.
    val rows = MountModel.rows(known, MountModel.seen(live, swept), views.map { it.attached })
    // The mount and letter dialogs sit over the whole window, so they are held here, by row id:
    // the row itself is re-read on every change rather than captured when the dialog opened.
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var picking by remember { mutableStateOf<PickRequest?>(null) }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(host, rows, views.size)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
            // Two columns while they fit; one scrolling column in a narrow window.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth
                if (wide >= 820.dp) {
                    Row(Modifier.fillMaxSize()) {
                        ScrollPane(Modifier.width((wide * 0.42f).coerceIn(380.dp, 480.dp)).fillMaxHeight()) { SharingColumn(host) { picking = it } }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.line))
                        ScrollPane(Modifier.weight(1f).fillMaxHeight()) { NetworkColumn(rows, mounts, rescan) { sheet = it } }
                    }
                } else {
                    ScrollPane(Modifier.fillMaxSize()) {
                        SharingColumn(host) { picking = it }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        NetworkColumn(rows, mounts, rescan) { sheet = it }
                    }
                }
            }
        }
        sheet?.let { s ->
            val row = rows.firstOrNull { it.id == s.id }
            if (row == null) {
                sheet = null
            } else {
                Box(
                    Modifier.fillMaxSize().background(Color(0x9E04080A))
                        // Swallows clicks, so nothing behind the dialog can be pressed while it is open.
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    when (s) {
                        is Sheet.Mount -> MountSheet(row, rows, mounts) { sheet = null }
                        is Sheet.Letter -> LetterSheet(row, rows, mounts) { sheet = null }
                    }
                }
            }
        }
        picking?.let { p ->
            Box(
                Modifier.fillMaxSize().background(Color(0x9E04080A))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) { FolderPicker(p) { picking = null } }
        }
    }
}

/** Which dialog is open, over which row. */
private sealed interface Sheet {
    val id: String
    data class Mount(override val id: String) : Sheet
    data class Letter(override val id: String) : Sheet
}

// ── the strip ───────────────────────────────────────────────────────────────────────────────

/** "Is it working", at a glance: sharing, this PC's address, what is on the network, autostart. */
@Composable
private fun StatusStrip(host: HostService, rows: List<MountModel.PhoneRow>, drives: Int) {
    val shares by host.shares.collectAsState()
    val running by host.running.collectAsState()
    val chosen by host.chosenAddress.collectAsState()
    val addresses by host.addresses.collectAsState()
    val port by host.port.collectAsState()
    val folders = plural(shares.size, "folder")
    // Wraps in a narrow window rather than pushing the switch off the edge.
    FlowRow(
        Modifier.fillMaxWidth().background(Ink.strip).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Stat("SHARING", if (running) "On · $folders" else "Off · $folders", if (running) Ink.ok else Ink.muted)
        Stat(
            "THIS PC",
            when {
                !running -> "Not sharing"
                chosen != null -> "${chosen!!.ip}:$port"
                addresses.size > 1 -> "${addresses.size} addresses"
                else -> addresses.firstOrNull()?.let { "${it.ip}:$port" } ?: "No network"
            },
            mono = running && (chosen != null || addresses.size == 1),
        )
        Stat("ON THIS NETWORK", "${rows.count { it.online }} online · ${plural(drives, "drive")}")
        Spacer(Modifier.weight(1f))
        val why = remember { Autostart.unavailable() }
        Box(Modifier.align(Alignment.CenterVertically)) { StartToggle(host, why) }
    }
}

@Composable
private fun StartToggle(host: HostService, why: String?) {
    // Read back from the OS, not from the saved preference: the switch shows what is written.
    var on by remember { mutableStateOf(Autostart.isEnabled()) }
    Toggle(on, "Start with this computer", enabled = why == null) { want ->
        on = Autostart.set(want)
        host.autostart = on
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Ink.text, mono: Boolean = false) {
    Column(
        Modifier.background(Ink.panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp).widthIn(min = 120.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Txt(label, 11, Ink.muted, spaced = true)
        Txt(value, 15, color, bold = true, mono = mono, oneLine = true)
    }
}

private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

// ── left: what this PC offers ───────────────────────────────────────────────────────────────

@Composable
private fun SharingColumn(host: HostService, pick: (PickRequest) -> Unit) {
    val shares by host.shares.collectAsState()
    val running by host.running.collectAsState()
    val endpoints by host.endpoints.collectAsState()
    val missing by host.missing.collectAsState()
    val chosen by host.chosenAddress.collectAsState()
    val port by host.port.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    // Re-read every twenty seconds, so changing the setting clears the warning without a restart.
    var netWarn by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (dev.niccc2007.filet.desktop.mount.Platform.current != dev.niccc2007.filet.desktop.mount.Platform.WINDOWS) return@LaunchedEffect
        while (true) {
            netWarn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NetCheck.warning(NetCheck.read()) }
            kotlinx.coroutines.delay(20_000)
        }
    }
    // One inline editor at a time, keyed by what it edits: "name:<path>", "code:<path>" or "port".
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    // The share whose QR is showing. One at a time: each holds an access code.
    var qr by remember { mutableStateOf<String?>(null) }

    SectionHead("SHARING FROM THIS PC") {
        if (running) Btn("Stop") { host.stop(); note = null }
        // Disabled with nothing to share, rather than pressed and then refused.
        else Btn("Start", enabled = shares.isNotEmpty(), primary = true) { note = host.start() }
        Btn("+ Add a folder", primary = running || shares.isEmpty()) {
            pick(PickRequest("Share a folder", "Share this folder", null) { note = host.addShare(it.path, writable = false) })
        }
    }
    netWarn?.let { w ->
        Panel(border = Ink.warnEdge) {
            Txt(w, 12.5, Ink.warn)
            Btn("Network settings") { NetCheck.openSettings() }
        }
    }
    // Several addresses and no evidence which one other devices reach: said in words, because
    // three unexplained URLs is the state in which the wrong one got copied.
    if (running && endpoints.any { !it.confident && it.urls.size > 1 }) {
        Txt(
            "This PC has more than one network address and Filet cannot tell which one other devices can reach. " +
                "Pick the one on the same network as the phone or PC that will connect.",
            12.5, Ink.warn,
        )
    }
    if (shares.isEmpty()) Txt("Nothing shared yet. Add a folder, or a whole drive, to offer it to your devices.", 12.5, Ink.muted)

    for (s in shares) key(s.path) {
        SharePanel(
            host = host,
            s = s,
            e = if (running) endpoints.firstOrNull { it.path == s.path } else null,
            gone = missing.any { it.path == s.path },
            editing = editing,
            draft = draft,
            qrOpen = qr == s.path,
            onDraft = { draft = it },
            onEdit = { key, start -> editing = key; draft = start; note = null },
            onDone = { editing = null },
            onQr = { qr = if (qr == s.path) null else s.path },
            onNote = { note = it },
            pick = pick,
        )
    }

    if (running) {
        chosen?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Using ${c.ip} on ${c.adapter}.", 12.5, Ink.muted, modifier = Modifier.weight(1f))
                Btn("Choose automatically", kind = BtnKind.GHOST) { host.chooseAddress(null) }
            }
        }
        // Stated in the window rather than in a help page: plain HTTP on a LAN is the honest
        // trade, and whoever holds the address holds the share.
        Txt("Plain HTTP on this network. Anyone with an address above can open that folder.", 12.5, Ink.muted)
    }
    if (editing == "port") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Port", 12.5, Ink.muted)
            Field(draft, { v -> draft = v.filter { it.isDigit() }.take(5) }, "1024-65535", Modifier.width(110.dp))
            Btn("Apply", enabled = draft.isNotBlank(), primary = true) {
                note = host.setPort(draft.toInt())
                if (note == null) editing = null
            }
            Btn("Cancel", kind = BtnKind.GHOST) { editing = null }
        }
        // Said here, not discovered: the server keeps the port it bound until it restarts.
        Txt("Takes effect the next time sharing starts. Stop and Start to apply it now.", 12.5, Ink.muted)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = if (running && port > 0) port else host.preferredPort
            Txt(
                "Port $shown" + if (running && port > 0 && port != host.preferredPort) " (${host.preferredPort} was taken)" else "",
                12.5, Ink.muted, modifier = Modifier.weight(1f),
            )
            Btn("Change port", kind = BtnKind.GHOST) { editing = "port"; draft = host.preferredPort.toString(); note = null }
        }
    }
    Autostart.unavailable()?.let { Txt("Start with this computer: $it", 12, Ink.muted) }
    note?.let { Txt(it, 13, Ink.warn) }
}

/** One shared folder: its name and permission, its address, its QR, and what can be done to it. */
@Composable
private fun SharePanel(
    host: HostService,
    s: Share,
    e: Endpoint?,
    gone: Boolean,
    editing: String?,
    draft: String,
    qrOpen: Boolean,
    onDraft: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDone: () -> Unit,
    onQr: () -> Unit,
    onNote: (String?) -> Unit,
    pick: (PickRequest) -> Unit,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(s.name, 15, bold = true, oneLine = true, modifier = Modifier.weight(1f))
            Tag(if (s.writable) "read+write" else "read only")
        }
        Txt(if (gone) "${s.path}  (folder not found)" else s.path, 12.5, if (gone) Ink.warn else Ink.muted, mono = true, oneLine = true)
        if (e != null) {
            if (e.confident || e.urls.size <= 1) {
                UrlRow(e.url)
            } else {
                // Not confident: e.url is only the first candidate, so it is never shown alone.
                for (u in e.urls) {
                    Txt(u.adapter, 12, Ink.muted)
                    UrlRow(u.url) { Btn("Use this one", kind = BtnKind.GHOST) { host.chooseAddress(u.ip) } }
                }
            }
        }
        when (editing) {
            "name:${s.path}" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(draft, onDraft, "share name", Modifier.weight(1f))
                Btn("Rename", enabled = draft.isNotBlank(), primary = true) { host.rename(s.path, draft.trim()); onDone() }
                Btn("Cancel", kind = BtnKind.GHOST) { onDone() }
            }
            "code:${s.path}" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(draft, onDraft, "letters and digits", Modifier.weight(1f))
                    Btn("Set", enabled = draft.isNotBlank(), primary = true) {
                        val why = host.setCode(s.path, draft.trim())
                        onNote(why)
                        if (why == null) onDone()
                    }
                    Btn("New random") {
                        val why = host.setCode(s.path, null)
                        onNote(why)
                        if (why == null) onDone()
                    }
                    Btn("Cancel", kind = BtnKind.GHOST) { onDone() }
                }
                Txt("The code stays the same every launch until it is changed here. A drive mapped to the old address stops working until it is mapped again.", 12, Ink.muted)
            }
        }
        val link = e?.let { ShareQr.link(host.name, host.deviceId, it) }
        if (qrOpen && link != null) QrBlock(link)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Rename") { onEdit("name:${s.path}", s.name) }
            // Same name, code and address; only the folder behind it changes.
            Btn("Change folder") {
                pick(PickRequest("Move ${s.name} to another folder", "Use this folder", s.path) { onNote(host.moveShare(s.path, it.path)) })
            }
            if (e != null) Btn("Change code") { onEdit("code:${s.path}", "") }
            Btn(if (s.writable) "Make read only" else "Make writable") { host.setWritable(s.path, !s.writable) }
            if (link != null) {
                Btn(if (qrOpen) "Hide QR" else "QR", primary = qrOpen) { onQr() }
                Btn("Copy link") { copy(link) }
            }
            Btn("Remove", kind = BtnKind.GHOST) { host.removeShare(s.path) }
        }
    }
}

@Composable
private fun UrlRow(url: String, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Ink.bg, RoundedCornerShape(8.dp)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(url, 13.5, Ink.url, mono = true, oneLine = true, modifier = Modifier.weight(1f))
        extra()
        Btn("Copy", kind = BtnKind.GHOST) { copy(url) }
    }
}

/** A share's QR above what it is for. The phone's scanner reads it; so does any camera. */
@Composable
private fun QrBlock(link: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Large on purpose: a share link is a dense code, and a PC screen has far fewer pixels
        // per inch than the phone screen the same code is drawn on there.
        FiletQr(link, Modifier.size(240.dp))
        Txt("Scan with Filet on your phone", 14, bold = true)
        Txt(
            "Adds this folder as a network drive. Scan again after this PC changes address and the drive is updated, not added twice.",
            12.5, Ink.muted,
        )
        Txt("Holds the access code. Show it only to devices that should have this folder.", 12.5, Ink.warn)
    }
}

// ── right: what is on the network ───────────────────────────────────────────────────────────

@Composable
private fun NetworkColumn(rows: List<MountModel.PhoneRow>, ctl: MountController, rescan: () -> Unit, open: (Sheet) -> Unit) {
    val mounts by ctl.mounts.collectAsState()
    val problem by ctl.problem.collectAsState()
    val letterFree by ctl.letterFree.collectAsState()
    val busy by ctl.busy.collectAsState()
    val errors by ctl.errors.collectAsState()
    val limit by ctl.limit.collectAsState()
    val notices by ctl.notices.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }

    SectionHead("ON THIS NETWORK") {
        // Discovery only hears what is announced; this asks again instead of waiting.
        val looking = "refresh" in busy || "sweep" in busy
        Btn(if (looking) "Looking..." else "Refresh", enabled = !looking) { ctl.refreshNow(rescan) }
    }
    for (n in notices) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(n, 13, Ink.accent, modifier = Modifier.weight(1f))
                Btn("OK") { ctl.dismiss(n) }
            }
        }
    }
    problem?.let { Txt(it, 13, Ink.warn) }
    if (problem == null && !letterFree) Txt("Every drive letter from D: to Z: is in use. Disconnect one to mount another.", 13, Ink.warn)
    (limit as? WebClientLimit.Verdict.Capped)?.let { c ->
        Panel(border = Ink.warnEdge) {
            Txt(
                "Windows stops any file over ${Probe.size(c.limit)} on a network drive, and the copy looks corrupt. " +
                    "Raising the limit asks for administrator permission and briefly reconnects network drives.",
                12.5, Ink.warn,
            )
            Btn(if ("limit" in busy) "Raising..." else "Raise the limit", enabled = "limit" !in busy) { ctl.raiseLimit() }
        }
    }
    if (rows.isEmpty()) Txt("Looking for phones and PCs running Filet with sharing on...", 13, Ink.muted)

    for (r in rows) key(r.id) {
        DeviceTile(
            r = r,
            view = r.drive?.let { d -> mounts.firstOrNull { it.attached == d } },
            ctl = ctl,
            problem = problem,
            working = r.id in busy,
            error = errors[r.id],
            renaming = renaming == r.id,
            draft = draft,
            onDraft = { draft = it },
            onRename = { renaming = r.id; draft = r.label },
            onRenamed = { renaming = null },
            open = open,
        )
    }
}

/** One device share: who it is, whether it is a drive here, how full it is, and the one thing to do next. */
@Composable
private fun DeviceTile(
    r: MountModel.PhoneRow,
    view: MountView?,
    ctl: MountController,
    problem: String?,
    working: Boolean,
    error: MountResult.Failed?,
    renaming: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onRename: () -> Unit,
    onRenamed: () -> Unit,
    open: (Sheet) -> Unit,
) {
    val mounted = r.state == MountModel.State.MOUNTED
    // Amber only for a drive that has stopped answering. A drive no announced share claims but
    // that answers is working, and is shown as working.
    val dead = mounted && view?.answering == false
    val moved = MountModel.movedTo(r)
    val what = r.peer?.let { p -> p.scope.ifBlank { "Shared folder" } + ", " + if (p.writable) "read+write" else "read only" }

    Panel(Modifier.alpha(if (r.state == MountModel.State.OFFLINE) 0.62f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeviceIcon(when { dead -> IconLook.WARN; r.online || mounted -> IconLook.LIVE; else -> IconLook.DIM })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(r.label, 15, bold = true, oneLine = true)
                val name = (r.peer?.name ?: r.phone?.name)?.takeIf { it != r.label }
                Txt(listOfNotNull(name, MountModel.addressLine(r).takeIf { it.isNotEmpty() }).joinToString(" · "), 12.5, Ink.muted, oneLine = true)
            }
            when {
                dead -> Pill("Not answering", PillKind.WARN)
                mounted -> Pill(MountModel.stateLine(r), PillKind.ON)
                r.state == MountModel.State.NOT_MOUNTED -> Pill("Not mounted", PillKind.IDLE)
                else -> Pill("Offline", PillKind.OFF)
            }
        }
        Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val space = view?.space
            if (mounted && space != null && !dead) {
                Meter(if (space.totalBytes > 0) space.usedBytes.toFloat() / space.totalBytes else 0f)
                Txt(
                    listOfNotNull("${Probe.size(space.usedBytes)} used", "${Probe.size(space.availableBytes)} free on the device", what).joinToString(" · "),
                    12.5, Ink.muted,
                )
            } else if (what != null) {
                Txt(what, 12.5, Ink.muted)
            }
            when {
                moved != null -> Txt("${r.label} is now at ${moved.host}:${moved.port}.", 12.5, Ink.warn)
                mounted && dead -> Txt(view?.note ?: "Not answering - the device may have left the network or stopped sharing.", 12.5, Ink.warn)
            }
            if (renaming) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(draft, onDraft, r.peer?.name ?: r.phone?.name ?: "name", Modifier.width(220.dp))
                    Btn("Save", enabled = draft.isNotBlank() && !working, primary = true) {
                        ctl.rename(r, draft) { err -> if (err == null) onRenamed() }
                    }
                    Btn("Cancel", kind = BtnKind.GHOST) { onRenamed() }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (r.state) {
                    MountModel.State.MOUNTED -> {
                        moved?.let { p -> Btn("Remount at ${p.host}:${p.port}", enabled = !working, primary = true) { ctl.remount(r, view) { } } }
                        Btn(if (ctl.usesLetters) "Open in Explorer" else "Open", enabled = !dead, primary = moved == null) { openFolder(r.drive!!.where) }
                        Btn(if (working) "Working..." else "Unmount", enabled = !working, kind = if (dead) BtnKind.WARN else BtnKind.NORMAL) { ctl.unmount(r, view) }
                        if (r.phone != null) Btn("Rename", enabled = !working) { onRename() }
                        if (ctl.usesLetters) Btn("Change letter", enabled = !working && !dead) { open(Sheet.Letter(r.id)) }
                    }
                    MountModel.State.NOT_MOUNTED -> {
                        Btn("Mount", enabled = problem == null && !working, primary = true) { open(Sheet.Mount(r.id)) }
                        if (r.phone != null) Btn("Rename", enabled = !working) { onRename() }
                        if (r.phone != null) Btn("Forget", enabled = !working, kind = BtnKind.GHOST) { ctl.forget(r) }
                    }
                    MountModel.State.OFFLINE -> {
                        if (r.phone != null) Btn("Rename", enabled = !working) { onRename() }
                        if (r.phone != null) Btn("Forget", enabled = !working, kind = BtnKind.GHOST) { ctl.forget(r) }
                    }
                }
            }
            error?.let { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(f.message, 12.5, Ink.warn, modifier = Modifier.weight(1f))
                    if (f.canForce && r.drive != null) Btn("Unmount anyway", kind = BtnKind.WARN) { ctl.unmount(r, view, force = true) }
                }
            }
        }
    }
}

/** Used against total, in the mark's two blues. */
@Composable
private fun Meter(used: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF22302F), RoundedCornerShape(3.dp))) {
        Box(
            Modifier.fillMaxWidth(used.coerceIn(0f, 1f)).height(6.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Ink.primary, Ink.accent)), RoundedCornerShape(3.dp)),
        )
    }
}

private enum class IconLook { LIVE, DIM, WARN }

/** The Filet folder mark for a device, dimmed when it is away; a warning sign for a drive nothing answers on. */
@Composable
private fun DeviceIcon(look: IconLook) {
    Box(
        Modifier.size(40.dp).background(if (look == IconLook.WARN) Color(0xFF2A2116) else Ink.tile, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (look == IconLook.WARN) {
            Canvas(Modifier.size(22.dp)) {
                scale(size.width / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(WARN_SIGN, Ink.warn) }
            }
        } else {
            FolderMark(Modifier.size(22.dp), dim = look == IconLook.DIM)
        }
    }
}

private val WARN_SIGN = PathParser().parsePathString(
    "M12 2 1 21h22L12 2zm0 6 1 7h-2l1-7zm0 9.5a1.2 1.2 0 1 1 0 2.4 1.2 1.2 0 0 1 0-2.4z",
).toPath()

// ── the dialogs ─────────────────────────────────────────────────────────────────────────────

/** Mounting is a dialog, not a form unfolding inside the list: a name, a letter, the code. */
@Composable
private fun MountSheet(r: MountModel.PhoneRow, rows: List<MountModel.PhoneRow>, ctl: MountController, close: () -> Unit) {
    val taken by ctl.taken.collectAsState()
    val problem by ctl.problem.collectAsState()
    val busy by ctl.busy.collectAsState()
    val errors by ctl.errors.collectAsState()
    var label by remember(r.id) { mutableStateOf(r.label) }
    var code by remember(r.id) { mutableStateOf(r.phone?.code.orEmpty()) }
    var picked by remember(r.id) { mutableStateOf<Char?>(null) }
    val working = r.id in busy
    val choices = if (ctl.usesLetters) MountModel.letterChoices(taken, filetLetters(rows)) else emptyList()
    val pick = choices.firstOrNull { it.letter == picked } ?: MountModel.firstChoice(choices, r.phone?.letter)
    val blocker = MountModel.mountBlocker(r.online, code, problem, !ctl.usesLetters || pick != null, working)

    SheetFrame {
        Txt("Mount ${r.label}", 16, bold = true)
        Txt(listOfNotNull(MountModel.addressLine(r).takeIf { it.isNotEmpty() }, r.peer?.scope?.ifBlank { null }).joinToString(" · "), 12.5, Ink.muted)
        SheetLabel(if (ctl.usesLetters) "Name in Explorer" else "Name")
        Field(label, { label = it }, r.peer?.name ?: "name", Modifier.fillMaxWidth())
        if (ctl.usesLetters) {
            SheetLabel("Drive letter")
            LetterChips(choices, pick) { picked = it }
            LetterNote(pick)
        }
        SheetLabel("Access code")
        Field(code, { code = AccessCode.typing(it) }, "shown on the device's sharing screen", Modifier.fillMaxWidth())
        errors[r.id]?.let { Txt(it.message, 12.5, Ink.warn) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(blocker ?: "The code is on the device's sharing screen. It is never broadcast.", 12.5, Ink.muted, modifier = Modifier.weight(1f))
            Btn("Cancel", kind = BtnKind.GHOST) { close() }
            Btn(if (working) "Mounting..." else "Mount", enabled = blocker == null, primary = true) {
                ctl.mount(r, label, code, pick) { err -> if (err == null) close() }
            }
        }
    }
}

/** Moving a mounted drive to another letter: the same chips, without the letter it is on. */
@Composable
private fun LetterSheet(r: MountModel.PhoneRow, rows: List<MountModel.PhoneRow>, ctl: MountController, close: () -> Unit) {
    val taken by ctl.taken.collectAsState()
    val busy by ctl.busy.collectAsState()
    val errors by ctl.errors.collectAsState()
    var picked by remember(r.id) { mutableStateOf<Char?>(null) }
    val working = r.id in busy
    val own = DriveLetters.letterOf(r.drive?.where)
    val choices = MountModel.letterChoices(taken, filetLetters(rows), own = own)
    val pick = choices.firstOrNull { it.letter == picked } ?: MountModel.firstChoice(choices, null)

    SheetFrame {
        Txt("Move ${r.label}", 16, bold = true)
        Txt(own?.let { "On $it: now." } ?: "", 12.5, Ink.muted)
        SheetLabel("New drive letter")
        LetterChips(choices, pick) { picked = it }
        LetterNote(pick)
        errors[r.id]?.let { Txt(it.message, 12.5, Ink.warn) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Btn("Cancel", kind = BtnKind.GHOST) { close() }
            Btn(if (working) "Moving..." else "Move", enabled = pick != null && !working && r.drive != null, primary = true) {
                ctl.changeLetter(r, pick!!) { err -> if (err == null) close() }
            }
        }
    }
}

@Composable
private fun SheetFrame(content: @Composable () -> Unit) {
    Column(
        Modifier.width(460.dp).background(Ink.panel, RoundedCornerShape(14.dp)).border(1.dp, Ink.edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun SheetLabel(s: String) = Txt(s, 12, Ink.muted, modifier = Modifier.padding(top = 2.dp))

/** Every letter a mount may take as a chip, Filet's own drawn amber as "replace X: (name)". */
@Composable
private fun LetterChips(choices: List<MountModel.LetterChoice>, pick: MountModel.LetterChoice?, onPick: (Char) -> Unit) {
    if (choices.isEmpty()) {
        Txt("No letter free.", 12.5, Ink.warn)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in choices) {
            val sel = c == pick
            val replace = c.replaces != null
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .background(
                        when { sel && replace -> Ink.warnEdge; sel -> Ink.primary; else -> Ink.btn }, shape,
                    )
                    .border(1.dp, when { replace -> Ink.warnEdge; sel -> Ink.accent; else -> Ink.edge }, shape)
                    .clickable { onPick(c.letter) }
                    .padding(horizontal = if (replace) 8.dp else 0.dp, vertical = 6.dp)
                    .then(if (replace) Modifier else Modifier.width(38.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Txt(c.text, 13, if (sel) Color.White else if (replace) Ink.warn else Ink.soft, mono = true)
            }
        }
    }
}

@Composable
private fun LetterNote(pick: MountModel.LetterChoice?) {
    Txt(
        if (pick?.replaces != null) "${pick.letter}: is ${pick.replaces}. It is unmounted first."
        else "Free letters from Z: down. Letters held by other drives are not offered.",
        12.5, if (pick?.replaces != null) Ink.warn else Ink.muted,
    )
}

/** Filet's own drive letters, with the name each row shows, for the replace chips. */
private fun filetLetters(rows: List<MountModel.PhoneRow>): Map<Char, String> =
    rows.mapNotNull { r -> r.drive?.let { d -> DriveLetters.letterOf(d.where)?.let { it to r.label } } }.toMap()

// ── outside the window ──────────────────────────────────────────────────────────────────────

/** Off the event thread: opening a drive whose server just went quiet blocks until it times out. */
private fun openFolder(where: String) {
    Thread {
        runCatching { java.awt.Desktop.getDesktop().open(File(if (where.endsWith(":")) "$where\\" else where)) }
    }.apply { isDaemon = true }.start()
}
