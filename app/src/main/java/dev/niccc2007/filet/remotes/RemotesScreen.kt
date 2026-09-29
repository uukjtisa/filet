package dev.niccc2007.filet.remotes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.EmptyNote
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.settings.SmallButton
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.tabs.EmptyTab
import dev.niccc2007.filet.ui.tabs.NLead
import dev.niccc2007.filet.ui.tabs.NRow
import dev.niccc2007.filet.ui.tabs.Pill
import dev.niccc2007.filet.ui.tabs.PillTone
import dev.niccc2007.filet.ui.tabs.SectionRow
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.tabs.TabHeader
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.RootProvider
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.webdav.DavBeacon

/**
 * Network shares and root, in one place.
 *
 * They belong together because they are the same idea: a volume this device can reach but
 * does not own. Once added, each one is an ordinary pane - the same rows, the same drag and
 * drop, the same search - because they are all just providers behind the VFS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemotesScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val revision by vm.remotesRevision.collectAsState()
    val list = remember(revision) { vm.connections.all() }
    var editing by remember { mutableStateOf<NetConnection?>(null) }

    // This screen's own interest in scanning, which is balanced and refcounted - the app keeps a
    // scan of its own running while it is in the foreground, so a saved remote learns the address
    // its device moved to without anybody visiting this screen. Stopping the scan on dispose here
    // used to stop that one too.
    val beacon = vm.davBeacon
    val found by beacon.state.collectAsState()
    DisposableEffect(Unit) {
        beacon.startScan()
        onDispose { beacon.stopScan() }
    }

    // Remotes a discovered Filet host could be. WebDAV only: a beacon is a Filet share, and
    // offering to link one to an SFTP entry would be offering something that cannot work.
    val linkable = remember(revision) { list.filter { it.protocol == NetProtocol.WEBDAV } }
    var linking by remember { mutableStateOf<DavBeacon.Host?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { TabHeader("Network and root") }

        // Other phones running Filet and hosting right now. Above the saved list on purpose:
        // adding one is the thing somebody is on this screen to do, and it used to mean reading
        // an address off the other phone and typing it in here by hand.
        if (found.hosts.isNotEmpty()) {
            item { SectionRow("Phones on this network") }
            items(found.hosts.size) { i ->
                val h = found.hosts[i]
                // A host that is already one of the saved remotes is not something to add, and
                // offering it as new is how one tablet ends up with four entries. The address it
                // is announced at may be one the entry has only just learned, so this is read
                // against the saved list rather than compared by eye.
                val already = remember(revision, h.key) { vm.savedRemoteFor(h) }
                NRow(
                    title = h.name,
                    sub = "${h.host}:${h.port}${if (h.scope.isNotEmpty()) "  ·  " + h.scope else ""}",
                    lead = NLead.Glyph(FiletIcons.Wifi),
                    mono = true,
                    trailing = {
                        if (already != null) {
                            Pill("saved", PillTone.Good)
                            SmallBtn("Open") { vm.openConnection(already) }
                        } else {
                            // Opens the form already filled in, so the only field left is the
                            // code - which is not broadcast, because a password that travels
                            // with the address is not a password.
                            SmallBtn("Add") { editing = vm.connectionFromHost(h) }
                            // For the one case learning cannot reach on its own: an entry with
                            // no device id whose address has already changed has nothing left to
                            // match on. Hidden when there is nothing to link to.
                            if (linkable.isNotEmpty()) SmallBtn("Link") { linking = h }
                        }
                    },
                )
            }
        }

        item { SectionRow("Network") }
        if (list.isEmpty()) {
            item {
                EmptyTab(
                    FiletIcons.Device,
                    "No shares yet",
                )
            }
        }
        items(list.size) { i ->
            val c = list[i]
            NRow(
                title = c.label.ifEmpty { c.host },
                sub = "${c.protocol.scheme}://${c.host}${if (c.share.isNotEmpty()) "/" + c.share else ""}",
                lead = NLead.Glyph(iconFor(c.protocol)),
                mono = true,
                trailing = {
                    // What the pill says has to be something that is actually known. Nothing
                    // here polls the share, so "connected" would be a guess dressed as a fact;
                    // whether the stored connection encrypts itself is not a guess.
                    val plain = !c.useTls &&
                        (c.protocol == NetProtocol.FTP || c.protocol == NetProtocol.WEBDAV)
                    if (plain) {
                        Pill(
                            if (c.protocol == NetProtocol.FTP) "plain FTP" else "plain HTTP",
                            PillTone.Running,
                        )
                    } else {
                        Pill(c.protocol.name, PillTone.Plain)
                    }
                    SmallBtn("Edit") { editing = c }
                },
                // The row itself opens it. This is what was missing entirely: the only control
                // was Edit, so a saved place could be created and then never reached.
                onClick = { vm.openConnection(c) },
            )
        }
        item {
            // ONE button.
            //
            // There used to be four, one per protocol, on the reasoning that the form differs by
            // protocol. It does - but a row of +SMB +SFTP +FTP +WEBDAV asks the reader to already
            // know which of four protocols their own PC speaks, which is the question they came
            // here unable to answer. The dialogue asks what the thing IS and picks the protocol
            // itself; see RemoteKinds.
            Column(
                Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 2.dp, bottom = 4.dp),
            ) {
                TabButton("Add a place", FiletIcons.Link, primary = true) {
                    editing = RemoteKinds.blank(RemoteKind.WINDOWS_PC, vm.connections.newId())
                }
            }
        }

        // Filet as the thing being connected TO, rather than the thing connecting.
        item { SectionRow("Mount this phone on your PC", note = "WebDAV") }
        item { HostingCard(vm) }

        item { SectionRow("Root") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(13.dp),
            ) {
                val granted = remember(revision) { RootProvider.isGranted() }
                Text(
                    if (granted) "Root access granted" else "Root access",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The whole filesystem, through a superuser shell. Only asked for on this button.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.7.sp,
                )
                Spacer(Modifier.height(11.dp))
                TabButton(
                    if (granted) "Open /" else "Request root",
                    if (granted) FiletIcons.Folder else FiletIcons.Key,
                ) { vm.enableRoot() }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { c ->
        ConnectionDialog(
            initial = c,
            // A connection the user has never saved starts on the "what are you connecting to"
            // chooser; an existing one goes straight to its fields.
            isNew = vm.connections.all().none { s -> s.id == c.id },
            onDismiss = { editing = null },
            onDelete = { vm.deleteConnection(c.id); editing = null },
            onSave = { vm.saveConnection(it); editing = null },
        )
    }

    linking?.let { h -> LinkHostDialog(vm, h, linkable) { linking = null } }
}

/**
 * Attach a discovered host to a remote that was saved by hand.
 *
 * The gap this closes, stated plainly because it is the one thing automatic learning cannot do:
 * an entry recognises a device by the id it advertises, and an entry typed in by hand has no id
 * until a host is seen at an address it already holds. If the address changed first - a new
 * network, a hotspot - there is nothing left to match on and the entry is stranded, while the
 * same device sits at the top of this screen looking like a stranger.
 *
 * One tap ends it for good. The entry takes the id and the address together, so the next change
 * of network is learned without being asked.
 */
@Composable
private fun LinkHostDialog(
    vm: BrowserViewModel,
    host: DavBeacon.Host,
    choices: List<NetConnection>,
    onDismiss: () -> Unit,
) {
    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            FiletIcons.Link,
            "Which remote is this?",
            sub = "${host.name} at ${host.host}:${host.port}",
            onClose = onDismiss,
        )
        DlgBody {
            DlgCaption(
                "The remote you pick learns this address and remembers which device it is, so " +
                    "it keeps up on its own the next time the network changes.",
            )
            Spacer(Modifier.height(6.dp))
            choices.forEach { c ->
                DlgPick(
                    icon = iconFor(c.protocol),
                    label = c.label.ifEmpty { c.host },
                    detail = (listOf(c.host) + c.altHosts).joinToString(", "),
                    tag = if (c.deviceId.isEmpty()) "no device yet" else null,
                    onClick = { vm.linkRemote(c.id, host); onDismiss() },
                )
            }
        }
        DlgFooter {
            DlgSpacer()
            DlgBtn("Cancel", BtnKind.PLAIN, fill = false, onClick = onDismiss)
        }
    }
}

private fun iconFor(p: NetProtocol) = when (p) {
    NetProtocol.SMB -> FiletIcons.Device
    NetProtocol.SFTP -> FiletIcons.Terminal
    NetProtocol.FTP -> FiletIcons.Link
    NetProtocol.WEBDAV -> FiletIcons.Wifi
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    multiline: Boolean = false,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            label = { Text(label, fontSize = 11.sp) },
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
        // A hint under the field it belongs to, not a paragraph at the top of the form. It
        // says what to type, which is the only thing worth saying next to an input.
        if (hint != null) {
            Text(
                hint,
                fontSize = 10.sp,
                color = Filet.colors.fg3,
                lineHeight = 13.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
    }
}
