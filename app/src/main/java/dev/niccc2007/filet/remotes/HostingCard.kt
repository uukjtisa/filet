package dev.niccc2007.filet.remotes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.map
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.tabs.ChipRow
import dev.niccc2007.filet.ui.tabs.MicroLabel
import dev.niccc2007.filet.ui.tabs.NoteStack
import dev.niccc2007.filet.ui.tabs.NLead
import dev.niccc2007.filet.ui.tabs.NRow
import dev.niccc2007.filet.ui.tabs.DotState
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgField
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgNote
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.webdav.DavScope
import dev.niccc2007.filet.webdav.DavShare
import dev.niccc2007.filet.webdav.DavShareState
import dev.niccc2007.filet.webdav.DavShares
import dev.niccc2007.filet.webdav.DavState
import dev.niccc2007.filet.webdav.WebDavServer

/**
 * Mount this phone on a computer.
 *
 * The other half of the Remotes tab. Everything above it on that screen connects Filet TO
 * something; this one makes Filet the thing being connected to, which is why it is here and
 * not on Nearby - Nearby hands a browser a page, this hands Explorer a drive letter.
 *
 * ## Why this is a list
 *
 * There used to be one share, so the card was one set of controls. One share meant one root and
 * one permission for every machine that mounted the phone - share a folder read-write with a
 * desktop and you had shared it read-write with everything else too. Several shares ride one
 * socket, distinguished by the code that was always the first thing in the path, so each row is
 * its own root, its own code, its own permission and its own clock.
 */
@Composable
fun HostingCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    val dav by vm.davState.collectAsState()
    var editing by remember { mutableStateOf<DavShare?>(null) }
    var editingPort by remember { mutableStateOf<String?>(null) }

    // Walked once per state change rather than on every recomposition: enumerating interfaces
    // is a syscall per interface and this card redraws on every request that arrives.
    // Keyed on the network as well: this is the address somebody types into Explorer, and the
    // whole point of watching for a move is that it changes underneath a card nothing else
    // invalidates.
    val moves by vm.state.map { it.networkMoves }.collectAsState(0L)
    val addresses = remember(dav.running, dav.port, moves) {
        if (dav.running) vm.hostAddresses() else emptyList()
    }
    val reachable = addresses.filter { it.kind.reachable }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HostHeader(dav)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))

        Column(Modifier.padding(11.dp)) {
            for (s in dav.shares) {
                ShareRow(
                    state = s,
                    port = dav.port,
                    clashing = s.id in dav.clashes,
                    reachable = reachable.map { it.ip },
                    onStart = { vm.startHosting(s.id) },
                    onStop = { vm.stopHosting(s.id) },
                    onEdit = { editing = s.share },
                    onWritable = { vm.setShareWritable(s.id, it) },
                    onCopy = { vm.copyText(it, "Address copied") },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (dav.running && reachable.isEmpty()) {
                DlgWarn("No network address right now. Join a Wi-Fi network or turn on a hotspot.")
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBtn("Add a share") { editing = vm.newShare() }
                Spacer(Modifier.width(8.dp))
                SmallBtn("Port ${if (dav.preferredPort > 0) dav.preferredPort else WebDavServer.DEFAULT_PORT}") {
                    editingPort = (if (dav.preferredPort > 0) dav.preferredPort else WebDavServer.DEFAULT_PORT).toString()
                }
                Spacer(Modifier.weight(1f))
                if (dav.running) SmallBtn("Stop all") { vm.stopAllHosting() }
            }
        }
    }

    // Who has it mounted, across every share, with a way to cut it off.
    for (c in dav.clients) {
        NRow(
            title = c.address,
            sub = "${c.agent.take(40)} · ${c.reads} read${if (c.reads == 1) "" else "s"}" +
                if (c.writes > 0) " · ${c.writes} written" else "",
            lead = NLead.Dot(DotState.Ok),
            titleMono = true,
            trailing = { SmallBtn("Revoke") { vm.kickHostClient(c.address) } },
        )
    }

    if (dav.running) {
        // Each of these is the only explanation for something that otherwise looks exactly
        // like a broken file or a broken app, so none of them can be deleted. Three of them
        // stacked as boxes turned the card into a page of caveats and pushed the controls off
        // the screen, so they are behind one line instead - shut until somebody wants them.
        Column(Modifier.padding(horizontal = 13.dp)) {
            NoteStack(
                "If Windows misbehaves",
                listOf(
                    "Windows stops WebDAV downloads at 50 MB until its registry limit is " +
                        "raised. A large file fails at exactly that size, which looks like a " +
                        "broken file and is not one. Copy it with a browser link instead, or " +
                        "raise FileSizeLimitInBytes.",
                    "Windows needs its WebClient service running. It is on by default; if the " +
                        "address will not open at all, check that first.",
                    "A mapped drive shows the PC's own free space, not the phone's. Windows " +
                        "asks for the figure, Filet sends it, and Explorer draws the C: drive's " +
                        "instead. Measured on both sides; there is nothing here to fix.",
                ),
            )
        }
    }

    editing?.let { share ->
        ShareDialog(
            share = share,
            existing = dav.shares.map { it.share },
            running = dav.shares.firstOrNull { it.id == share.id }?.running == true,
            onPickFolder = { vm.pickShareFolder(share.id); editing = null },
            onDelete = { vm.deleteShare(share.id); editing = null },
            onSave = { vm.saveShare(it); editing = null },
            onDismiss = { editing = null },
        )
    }

    editingPort?.let { current ->
        PortDialog(
            value = current,
            onValue = { editingPort = it },
            onSave = {
                current.toIntOrNull()?.let { vm.setHostPort(it) }
                editingPort = null
            },
            onDismiss = { editingPort = null },
        )
    }
}

@Composable
private fun HostHeader(dav: DavState) {
    val colors = Filet.colors
    val up = dav.liveShares.size
    Row(
        Modifier.fillMaxWidth().padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (dav.running) colors.good else colors.fg3),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (dav.running) "Hosting" else "Not hosting",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    // The promise, on the line somebody reads before pressing Start. It used
                    // to live in the Windows instructions, which is the wrong place for the
                    // one fact that answers "is my phone being uploaded somewhere".
                    !dav.running -> "Your phone is the server - nothing leaves your network"
                    dav.liveShares.any { it.writable } ->
                        "$up of ${dav.shares.size} sharing · changes allowed"
                    dav.clients.isEmpty() ->
                        "$up of ${dav.shares.size} sharing · nothing mounted yet"
                    dav.clients.size == 1 -> "$up of ${dav.shares.size} sharing · 1 computer"
                    else -> "$up of ${dav.shares.size} sharing · ${dav.clients.size} computers"
                },
                fontSize = 10.5.sp,
                color = colors.fg3,
            )
        }
    }
}

/**
 * One share: what it is, whether it is up, and the address while it is.
 *
 * The addresses are on the row rather than in a section of their own because they belong to a
 * share and not to the phone - with several shares up there is no such thing as "the address".
 */
@Composable
private fun ShareRow(
    state: DavShareState,
    port: Int,
    clashing: Boolean,
    reachable: List<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onWritable: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = Filet.colors
    val s = state.share
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (state.running) colors.accent.copy(alpha = 0.06f) else colors.sunken)
            .border(1.dp, if (state.running) colors.accent else MaterialTheme.colorScheme.outline, shape)
            .padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (state.running) colors.good else colors.fg3),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildList {
                        add(s.rootLabel)
                        add(if (state.writable) "read and write" else "read only")
                        add(if (s.neverIdles) "stays up" else "stops after ${s.idleMinutes}m")
                        if (s.autoStart && DavShares.canAutoStart(s)) add("opens with Filet")
                    }.joinToString(" · "),
                    fontSize = 9.5.sp,
                    color = colors.fg3,
                )
            }
            SmallBtn("Edit", onClick = onEdit)
            Spacer(Modifier.width(6.dp))
            if (state.running) SmallBtn("Stop", onClick = onStop) else SmallBtn("Start", onClick = onStart)
        }

        if (clashing) {
            Spacer(Modifier.height(8.dp))
            // Two shares on one code means the second is unreachable with no error anywhere, so
            // the card has to be the thing that says it.
            DlgWarn("Another share uses this same code. Give one of them a different code.", bad = true)
        }

        if (state.running && reachable.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            MicroLabel("Windows", Modifier.padding(bottom = 4.dp))
            for (ip in reachable) {
                // The UNC form, not the URL. Explorer will not accept an http:// address in
                // its address bar for a drive mapping - the @port and DavWWWRoot spelling is
                // what makes Windows route it to its WebDAV client instead of to a web fetch.
                UncRow(ip, "\\\\$ip@$port\\DavWWWRoot\\a\\${state.code}", onCopy)
            }
            // Named for what you DO with it. The add-a-place form on the other phone has a
            // "Paste the address" box as its first field, and this is the string that goes in it.
            MicroLabel(
                "Mac, Linux, or paste into another phone",
                Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            for (ip in reachable) {
                UncRow(ip, DavShares.endpoint(ip, port, state.code), onCopy)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBtn(if (state.writable) "Switch to read only" else "Allow changes") {
                    onWritable(!state.writable)
                }
            }
        }
    }
}

/**
 * The editor for one share.
 *
 * Ordered by what somebody actually decides: what to call it, what is in it, who may change it,
 * and only then the endpoint details that most people never touch.
 */
@Composable
private fun ShareDialog(
    share: DavShare,
    existing: List<DavShare>,
    running: Boolean,
    onPickFolder: () -> Unit,
    onDelete: () -> Unit,
    onSave: (DavShare) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(share.id) { mutableStateOf(share) }
    var code by remember(share.id) { mutableStateOf(share.code.orEmpty()) }
    val others = existing.filter { it.id != share.id }
    val codeTaken = code.isNotBlank() && !DavShares.codeAvailable(others, code)
    val isNew = existing.none { it.id == share.id }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Wifi,
            title = if (isNew) "New share" else draft.label,
            sub = "What this phone offers to the network",
            onClose = onDismiss,
        )
        DlgBody {
            DlgField("Name", draft.label, { draft = draft.copy(label = it) }, hint = "Photos")

            DlgSection("What is in it")
            if (draft.customRoot == null) {
                ChipRow(
                    "Preset",
                    DavScope.entries.map { it.label },
                    draft.scope.label,
                ) { picked ->
                    draft = draft.copy(scope = DavScope.entries.first { it.label == picked })
                }
                Spacer(Modifier.height(6.dp))
                SmallBtn("Or pick a folder", onClick = onPickFolder)
            } else {
                Text(
                    draft.customRoot.orEmpty(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Filet.colors.fg3,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    SmallBtn("Change", onClick = onPickFolder)
                    Spacer(Modifier.width(6.dp))
                    SmallBtn("Use a preset") { draft = draft.copy(customRoot = null) }
                }
            }

            DlgSection("While it is running")
            DlgTick(
                on = draft.writable,
                label = "Allow changes",
                sub = "Off means whatever mounts this can read and copy, but not write, rename " +
                    "or delete. Remembered, so a share that opens with Filet opens the same way.",
            ) { draft = draft.copy(writable = !draft.writable) }
            Spacer(Modifier.height(8.dp))
            DlgTick(
                on = draft.views,
                label = "Show the ${dev.niccc2007.filet.webdav.DavViews.ROOT} folders",
                sub = "Recent, Images, Videos and Documents, flattened out of the index - a " +
                    "phone is miserable to navigate as a plain tree from a desktop.",
            ) { draft = draft.copy(views = !draft.views) }

            DlgSection("When it stops")
            ChipRow(
                "Idle",
                IDLE_CHOICES.map { if (it <= 0) "Never" else "${it}m" },
                if (draft.neverIdles) "Never" else "${draft.idleMinutes}m",
            ) { picked ->
                draft = draft.copy(
                    idleMinutes = if (picked == "Never") 0 else picked.removeSuffix("m").toIntOrNull() ?: 30,
                )
            }
            if (draft.neverIdles) {
                Spacer(Modifier.height(6.dp))
                // Asked for, and worth being plain about: this is a socket that stays open until
                // it is stopped by hand or the app is closed.
                // A consequence of the choice just made, not a fault standing in the way.
                DlgCaption("Stays up for as long as Filet is running. Stop it yourself when done.")
            }

            DlgSection("Opening on its own")
            DlgTick(
                on = draft.autoStart,
                label = "Start this share when Filet opens",
                sub = if (code.isBlank()) {
                    "Needs a fixed code below - a new code each time means a new address, " +
                        "which is what breaks a saved drive on the PC."
                } else {
                    "Comes up on its own, on the same address every time."
                },
                enabled = code.isNotBlank(),
            ) { draft = draft.copy(autoStart = !draft.autoStart) }

            DlgSection("Address")
            DlgField(
                "Access code",
                code,
                { code = it.filter { c -> c.isLetterOrDigit() } },
                hint = "leave empty for a new one each time",
            )
            if (codeTaken) {
                DlgWarn("Another share already uses this code.", bad = true)
                Spacer(Modifier.height(8.dp))
            }
            DlgNote(
                "What the code is for",
                "It is the password, and it is part of the address: " +
                    "http://phone:port/a/CODE. Pinning it keeps a mapped drive on a PC working " +
                    "between restarts. Leaving it empty is safer and means a new address each time.",
            )

            if (running && DavShares.needsRestart(share, draft.copy(code = code.trim().takeIf { it.isNotEmpty() }))) {
                Spacer(Modifier.height(10.dp))
                // Only the endpoint waits. Saying this about every setting is what made the
                // write toggle look saved-and-ignored.
                // Informational: the save works, it applies on the next start.
                DlgCaption("This changes the address. Restart this share for it to take effect.")
            }
        }
        DlgFooter {
            if (!isNew) {
                DlgBtn("Delete", kind = BtnKind.DANGER, onClick = onDelete)
            }
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                "Save",
                kind = BtnKind.PRIMARY,
                enabled = !codeTaken,
                onClick = {
                    val wanted = code.trim().takeIf { it.isNotEmpty() }
                    onSave(
                        draft.copy(
                            code = wanted,
                            // A switch that cannot be honoured is not stored as on. The condition
                            // is in DavShares so the card and the server cannot disagree about it.
                            autoStart = draft.autoStart && wanted != null,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun PortDialog(
    value: String,
    onValue: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Wifi,
            title = "Port",
            sub = "Shared by every share on this phone",
            onClose = onDismiss,
        )
        DlgBody {
            DlgField("Port", value, { onValue(it.filter { c -> c.isDigit() }.take(5) ) }, numeric = true)
            DlgNote(
                "If it will not bind",
                "Anything from 1024 to 65535. If the port is already taken, Filet takes any " +
                    "free one instead rather than refusing to start - the card shows what it " +
                    "actually got.",
            )
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", onClick = onDismiss)
            DlgBtn(
                "Save",
                kind = BtnKind.PRIMARY,
                enabled = value.toIntOrNull()?.let { it in 1024..65535 } == true,
                onClick = onSave,
            )
        }
    }
}

/**
 * An address, whole, with a copy button.
 *
 * Deliberately wrapped rather than ellipsised: truncating the middle of an address with an
 * ellipsis is fine for a label you tap and useless for one you have to read out.
 */
@Composable
private fun UncRow(kind: String, text: String, onCopy: (String) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.raised)
            .clickable { onCopy(text) }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind, fontSize = 8.5.sp, color = colors.fg3)
            Text(
                text,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(FiletIcons.Copy, null, Modifier.size(15.dp), tint = colors.fg3)
    }
}

/** Zero is Never, and it is first because "stays up" is the thing people go looking for. */
private val IDLE_CHOICES = listOf(0, 10, 30, 120)
