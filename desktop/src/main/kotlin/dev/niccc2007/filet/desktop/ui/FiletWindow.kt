package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.desktop.Autostart
import dev.niccc2007.filet.desktop.HostService
import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.DriveLetters
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.mount.WebClientLimit
import dev.niccc2007.filet.webdav.AccessCode
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The one window: sharing from this PC, phones on this network, start with this computer.
 *
 * No menus and no tabs, so everything the tool does is visible at once. A tab would hide one half
 * of a tool whose point is that the two halves are equal.
 */
@Composable
fun FiletWindow(host: HostService, peers: StateFlow<List<Peer>>, mounts: MountController, rescan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card { SharingSection(host) }
        Card { PhonesSection(peers, mounts, rescan) }
        Card { StartupSection(host) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Ink.card, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(16.dp),
    ) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun SharingSection(host: HostService) {
    val shares by host.shares.collectAsState()
    val running by host.running.collectAsState()
    val endpoints by host.endpoints.collectAsState()
    val missing by host.missing.collectAsState()
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

    SectionHead("SHARING FROM THIS PC") {
        if (running) Btn("Stop") { host.stop(); note = null }
        // Disabled with nothing to share, rather than pressed and then refused.
        else Btn("Start", enabled = shares.isNotEmpty(), primary = true) { note = host.start() }
    }
    netWarn?.let { w ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(w, 12, Ink.warn, modifier = Modifier.weight(1f))
            Btn("Network settings") { NetCheck.openSettings() }
        }
    }
    // One inline editor at a time, keyed by what it edits: "name:<path>", "code:<path>" or "port".
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    fun edit(key: String, start: String) { editing = key; draft = start; note = null }

    for (s in shares) {
        val gone = missing.any { it.path == s.path }
        if (editing == "name:${s.path}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(draft, { draft = it }, "share name", Modifier.weight(1f))
                Btn("Rename", enabled = draft.isNotBlank(), primary = true) { host.rename(s.path, draft.trim()); editing = null }
                Btn("Cancel") { editing = null }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(s.name, bold = true, modifier = Modifier.width(110.dp))
                Txt(if (gone) "${s.path}  (folder not found)" else s.path, 13, if (gone) Ink.warn else Ink.muted, modifier = Modifier.weight(1f))
                Btn("Rename") { edit("name:${s.path}", s.name) }
                // Same name, code and address; only the folder behind it changes.
                Btn("Change folder") { pickFolder()?.let { note = host.moveShare(s.path, it.path) } }
                Btn(if (s.writable) "read+write" else "read only") { host.setWritable(s.path, !s.writable) }
                Btn("Remove") { host.removeShare(s.path) }
            }
        }
    }
    Btn("+ Add a folder") {
        pickFolder()?.let { note = host.addShare(it.path, writable = false) }
    }
    if (running) {
        Spacer(Modifier.height(4.dp))
        val chosen by host.chosenAddress.collectAsState()
        // Several addresses and no evidence which one the phone shares: said in words, because
        // three unexplained URLs is the state in which the wrong one got copied.
        if (endpoints.any { !it.confident && it.urls.size > 1 }) {
            Txt(
                "This PC has more than one network address and Filet cannot tell which one your phone can reach. " +
                    "Pick the one matching your phone's network.",
                12, Ink.warn,
            )
        }
        for (e in endpoints) {
            if (e.confident || e.urls.size <= 1) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(e.name, 13, Ink.muted, modifier = Modifier.width(110.dp))
                    Txt(e.url, 15, mono = true, bold = true, modifier = Modifier.weight(1f))
                    Btn("Copy") { copy(e.url) }
                }
            } else {
                // Not confident: e.url is only the first candidate, so it is never shown alone.
                Txt(e.name, 13, Ink.muted)
                for (u in e.urls) {
                    Row(
                        Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Txt(u.adapter, 12, Ink.muted, modifier = Modifier.width(98.dp))
                        Txt(u.url, 14, mono = true, modifier = Modifier.weight(1f))
                        Btn("Copy") { copy(u.url) }
                        Btn("Use this one") { host.chooseAddress(u.ip) }
                    }
                }
            }
            if (editing == "code:${e.path}") {
                Row(
                    Modifier.padding(start = 120.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Field(draft, { draft = it }, "letters and digits", Modifier.width(180.dp))
                    Btn("Set", enabled = draft.isNotBlank(), primary = true) {
                        note = host.setCode(e.path, draft.trim())
                        if (note == null) editing = null
                    }
                    Btn("New random") {
                        note = host.setCode(e.path, null)
                        if (note == null) editing = null
                    }
                    Btn("Cancel") { editing = null }
                }
                Txt(
                    "A drive already mapped to the old address stops working until it is mapped again.",
                    12, Ink.muted, modifier = Modifier.padding(start = 120.dp),
                )
            } else {
                Row(
                    Modifier.padding(start = 120.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The code is already persistent; nothing on screen said so, which is the
                    // whole of why it read as missing.
                    Txt("Code stays the same every launch.", 12, Ink.muted, modifier = Modifier.weight(1f))
                    Btn("Change code") { edit("code:${e.path}", "") }
                }
            }
        }
        chosen?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("Using ${c.ip} on ${c.adapter}.", 12, Ink.muted, modifier = Modifier.weight(1f))
                Btn("Choose automatically") { host.chooseAddress(null) }
            }
        }
        // Stated in the window rather than in a help page: plain HTTP on a LAN is the honest
        // trade, and whoever holds the address holds the share.
        Txt("Plain HTTP on this network. Anyone who has an address above can open that folder.", 12, Ink.muted)
    }
    val port by host.port.collectAsState()
    if (editing == "port") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt("Port", 13, Ink.muted)
            Field(draft, { v -> draft = v.filter { it.isDigit() }.take(5) }, "1024-65535", Modifier.width(110.dp))
            Btn("Apply", enabled = draft.isNotBlank(), primary = true) {
                note = host.setPort(draft.toInt())
                if (note == null) editing = null
            }
            Btn("Cancel") { editing = null }
        }
        // Said here, not discovered: the server keeps the port it bound until it restarts.
        Txt("Takes effect the next time sharing starts. Stop and Start to apply it now.", 12, Ink.muted)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = if (running && port > 0) port else host.preferredPort
            Txt(
                "Port $shown" + if (running && port > 0 && port != host.preferredPort) " (${host.preferredPort} was taken)" else "",
                12, Ink.muted, modifier = Modifier.weight(1f),
            )
            Btn("Change port") { edit("port", host.preferredPort.toString()) }
        }
    }
    note?.let { Txt(it, 13, Ink.warn) }
}

@Composable
private fun PhonesSection(peerFlow: StateFlow<List<Peer>>, ctl: MountController, rescan: () -> Unit) {
    val peers by peerFlow.collectAsState()
    val known by ctl.known.collectAsState()
    val mounts by ctl.mounts.collectAsState()
    val taken by ctl.taken.collectAsState()
    val problem by ctl.problem.collectAsState()
    val letterFree by ctl.letterFree.collectAsState()
    val busy by ctl.busy.collectAsState()
    val errors by ctl.errors.collectAsState()
    val limit by ctl.limit.collectAsState()
    val swept by ctl.swept.collectAsState()
    val notices by ctl.notices.collectAsState()
    // One editor open at a time, named "<what>:<row id>" where what is mount, rename or letter.
    var open by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val picks = remember { mutableStateMapOf<String, Char>() }

    SectionHead("PHONES") {
        // Discovery only hears what is announced; this asks again instead of waiting.
        val looking = "refresh" in busy || "sweep" in busy
        Btn(if (looking) "Looking..." else "Refresh", enabled = !looking) { ctl.refreshNow(rescan) }
    }
    for (n in notices) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(n, 13, Ink.accent, modifier = Modifier.weight(1f))
            Btn("OK") { ctl.dismiss(n) }
        }
    }
    problem?.let { Txt(it, 13, Ink.warn) }
    if (problem == null && !letterFree) Txt("Every drive letter from D: to Z: is in use. Disconnect one to mount another.", 13, Ink.warn)

    (limit as? WebClientLimit.Verdict.Capped)?.let { c ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(
                "Windows stops any file over ${Probe.size(c.limit)} on a network drive, and the copy looks corrupt. " +
                    "Raising the limit asks for administrator permission and briefly reconnects network drives.",
                12, Ink.warn, modifier = Modifier.weight(1f),
            )
            Btn(if ("limit" in busy) "Raising..." else "Raise the limit", enabled = "limit" !in busy) { ctl.raiseLimit() }
        }
    }

    // Saved phones stay listed while offline; a phone on the network never mounted here follows.
    // Rows are rebuilt from the live list and the OS's drives on every change, so a phone that
    // leaves turns its row offline, and any open mount dialog under it stops offering Mount.
    val rows = MountModel.rows(known, MountModel.seen(peers, swept), mounts.map { it.attached })
    if (rows.isEmpty()) Txt("Looking for phones running Filet with sharing on...", 13, Ink.muted)
    val filetLetters = rows.mapNotNull { r -> r.drive?.let { d -> DriveLetters.letterOf(d.where)?.let { it to r.label } } }.toMap()

    for (r in rows) key(r.id) {
        val id = r.id
        val view = r.drive?.let { d -> mounts.firstOrNull { it.attached == d } }
        val dead = view?.answering == false
        val working = id in busy
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if (r.online) Ink.accent else Ink.muted, CircleShape))
                Txt(r.label, bold = true)
                Txt(MountModel.stateLine(r), 13, if (r.state == MountModel.State.MOUNTED) Ink.accent else Ink.muted, modifier = Modifier.weight(1f))
            }
            val name = r.peer?.name ?: r.phone?.name
            val address = MountModel.addressLine(r)
            val what = r.peer?.let { p -> listOf(p.scope.ifBlank { "Shared folder" }, if (p.writable) "read+write" else "read only").joinToString(" · ") }
            Txt(listOfNotNull(name?.takeIf { it != r.label }, address.takeIf { it.isNotEmpty() }, what).joinToString(" · "), 13, Ink.muted,
                modifier = Modifier.padding(start = 16.dp))

            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.state == MountModel.State.MOUNTED) {
                    Btn(if (ctl.usesLetters) "Open in Explorer" else "Open", enabled = !dead) { openFolder(r.drive!!.where) }
                    Btn(if (working) "Working..." else "Unmount", enabled = !working) { ctl.unmount(r, view) }
                    MountModel.movedTo(r)?.let { p ->
                        Btn("Remount at ${p.host}:${p.port}", enabled = !working, primary = true) { ctl.remount(r, view) { } }
                    }
                } else {
                    Btn("Mount", enabled = r.online && problem == null && !working, primary = true) {
                        drafts["name:$id"] = r.label
                        drafts["code:$id"] = r.phone?.code.orEmpty()
                        open = "mount:$id"
                    }
                }
                if (r.phone != null) Btn("Rename", enabled = !working) { drafts["name:$id"] = r.label; open = "rename:$id" }
                if (r.state == MountModel.State.MOUNTED && ctl.usesLetters) Btn("Change letter", enabled = !working && !dead) { open = "letter:$id" }
                if (r.phone != null && r.state != MountModel.State.MOUNTED) Btn("Forget", enabled = !working) { ctl.forget(r) }
                if (open?.endsWith(":$id") == true) Btn("Cancel") { open = null }
            }

            val pad = Modifier.padding(start = 16.dp)
            when (open) {
                "mount:$id" -> if (r.state != MountModel.State.MOUNTED) {
                    val choices = if (ctl.usesLetters) MountModel.letterChoices(taken, filetLetters) else emptyList()
                    val pick = choices.firstOrNull { it.letter == picks[id] } ?: MountModel.firstChoice(choices, r.phone?.letter)
                    val label = drafts["name:$id"].orEmpty()
                    val code = drafts["code:$id"].orEmpty()
                    val blocker = MountModel.mountBlocker(r.online, code, problem, !ctl.usesLetters || pick != null, working)
                    Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("Name", 13, Ink.muted, modifier = Modifier.width(84.dp))
                        Field(label, { drafts["name:$id"] = it }, name ?: "name", Modifier.width(220.dp))
                    }
                    if (ctl.usesLetters) LetterPicker(choices, pick, pad) { picks[id] = it }
                    Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("Access code", 13, Ink.muted, modifier = Modifier.width(84.dp))
                        Field(code, { drafts["code:$id"] = AccessCode.typing(it) }, "access code", Modifier.width(160.dp))
                        Btn(if (working) "Mounting..." else "Mount", enabled = blocker == null, primary = true) {
                            ctl.mount(r, label, code, pick) { err -> if (err == null) open = null }
                        }
                    }
                    if (errors[id] == null) Txt(blocker ?: "The code is on the device's sharing screen. It is never broadcast.", 12, Ink.muted, modifier = pad)
                }
                "rename:$id" -> Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = drafts["name:$id"].orEmpty()
                    Field(label, { drafts["name:$id"] = it }, name ?: "name", Modifier.width(220.dp))
                    Btn("Save", enabled = label.isNotBlank() && !working, primary = true) {
                        ctl.rename(r, label) { err -> if (err == null) open = null }
                    }
                }
                "letter:$id" -> r.drive?.let { d ->
                    val choices = MountModel.letterChoices(taken, filetLetters, own = DriveLetters.letterOf(d.where))
                    val pick = choices.firstOrNull { it.letter == picks[id] } ?: MountModel.firstChoice(choices, null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LetterPicker(choices, pick, pad) { picks[id] = it }
                        Btn(if (working) "Moving..." else "Move", enabled = pick != null && !working, primary = true) {
                            ctl.changeLetter(r, pick!!) { err -> if (err == null) open = null }
                        }
                    }
                }
            }

            if (r.state == MountModel.State.MOUNTED) {
                val status = when {
                    dead -> view?.note ?: "Not answering - the device may have left the network or stopped sharing."
                    view?.space != null -> Probe.describe(view.space) + " on the device. Explorer shows this PC's own free space instead."
                    else -> null
                }
                status?.let { Txt(it, 12, if (dead) Ink.warn else Ink.muted, modifier = pad) }
            }
            errors[id]?.let { f ->
                Row(pad, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(f.message, 12, Ink.warn, modifier = Modifier.weight(1f))
                    if (f.canForce && r.drive != null) Btn("Unmount anyway") { ctl.unmount(r, view, force = true) }
                }
            }
        }
    }
}

/** Steps through [choices]; a replacement is drawn in the warning colour, since it unmounts a drive. */
@Composable
private fun LetterPicker(choices: List<MountModel.LetterChoice>, pick: MountModel.LetterChoice?, modifier: Modifier, onPick: (Char) -> Unit) {
    val i = choices.indexOf(pick)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Drive letter", 13, Ink.muted, modifier = Modifier.width(84.dp))
        Btn("<", enabled = i > 0) { onPick(choices[i - 1].letter) }
        Txt(pick?.text ?: "no letter free", 13, if (pick?.replaces != null) Ink.warn else Ink.text, mono = true)
        Btn(">", enabled = i in 0 until choices.lastIndex) { onPick(choices[i + 1].letter) }
    }
}

@Composable
private fun StartupSection(host: HostService) {
    val why = remember { Autostart.unavailable() }
    // Read back from the OS, not from the saved preference: the box shows what is written.
    var on by remember { mutableStateOf(Autostart.isEnabled()) }
    Check(on, "Start with this computer", enabled = why == null) { want ->
        on = Autostart.set(want)
        host.autostart = on
    }
    why?.let { Txt(it, 12, Ink.muted, modifier = Modifier.padding(start = 24.dp)) }
}

/** A folder picker. Swing's, because AWT's `FileDialog` cannot pick a directory on Windows. */
private fun pickFolder(): File? {
    val ch = javax.swing.JFileChooser().apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Share a folder"
    }
    return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

/** Off the event thread: opening a drive whose server just went quiet blocks until it times out. */
private fun openFolder(where: String) {
    Thread {
        runCatching { java.awt.Desktop.getDesktop().open(File(if (where.endsWith(":")) "$where\\" else where)) }
    }.apply { isDaemon = true }.start()
}
