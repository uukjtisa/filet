package dev.niccc2007.filet.remotes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.RootProvider
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * Network shares and root, in one place.
 *
 * They belong together because they are the same idea: a volume this device can reach but
 * does not own. Once added, each one is an ordinary pane - the same rows, the same drag and
 * drop, the same search - because they are all just providers behind the VFS.
 */
@Composable
fun RemotesScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val revision by vm.remotesRevision.collectAsState()
    val list = remember(revision) { vm.connections.all() }
    var editing by remember { mutableStateOf<NetConnection?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Network") }
        if (list.isEmpty()) {
            item { EmptyNote("No shares yet.", Modifier.fillMaxWidth().height(80.dp)) }
        }
        items(list.size) { i ->
            val c = list[i]
            Row(
                Modifier.fillMaxWidth().clickable { vm.openRemote(c) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(iconFor(c.protocol), null, tint = colors.accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.label.ifEmpty { c.host }, fontSize = 13.sp)
                    Text(
                        "${c.protocol.scheme}://${c.host}${if (c.share.isNotEmpty()) "/" + c.share else ""}",
                        fontSize = 10.sp, color = colors.fg3, fontFamily = FontFamily.Monospace,
                    )
                }
                SmallButton("Edit") { editing = c }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetProtocol.entries.forEach { proto ->
                    SmallButton("+ ${proto.name}") {
                        editing = NetConnection(
                            id = vm.connections.newId(),
                            protocol = proto,
                            label = "",
                            host = "",
                            port = proto.defaultPort,
                            user = "",
                            password = "",
                        )
                    }
                }
            }
        }

        item { SectionLabel("Root") }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                    .background(colors.raised).padding(12.dp),
            ) {
                val granted = remember(revision) { RootProvider.isGranted() }
                Text(
                    if (granted) "Root access granted" else "Root access",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Browses the whole filesystem through a managed superuser shell. Asking " +
                        "for it triggers your superuser prompt, so Filet only asks when you " +
                        "press this — never at startup.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                )
                Spacer(Modifier.height(9.dp))
                SmallButton(if (granted) "Open /" else "Request root") { vm.enableRoot() }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { c ->
        ConnectionDialog(
            initial = c,
            onDismiss = { editing = null },
            onDelete = { vm.deleteConnection(c.id); editing = null },
            onSave = { vm.saveConnection(it); editing = null },
        )
    }
}

private fun iconFor(p: NetProtocol) = when (p) {
    NetProtocol.SMB -> FiletIcons.Device
    NetProtocol.SFTP -> FiletIcons.Terminal
    NetProtocol.FTP -> FiletIcons.Link
    NetProtocol.WEBDAV -> FiletIcons.Wifi
}

@Composable
private fun ConnectionDialog(
    initial: NetConnection,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (NetConnection) -> Unit,
) {
    val colors = Filet.colors
    var label by remember { mutableStateOf(initial.label) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var share by remember { mutableStateOf(initial.share) }
    var anonymous by remember { mutableStateOf(initial.anonymous) }
    var tls by remember { mutableStateOf(initial.useTls) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${initial.protocol.label}", fontSize = 15.sp) },
        text = {
            Column {
                Field("Name", label) { label = it }
                Field("Host", host) { host = it }
                Field("Port", port) { port = it }
                if (initial.protocol == NetProtocol.SMB) Field("Share", share) { share = it }
                if (initial.protocol == NetProtocol.WEBDAV) Field("Base path", share) { share = it }
                if (!anonymous) {
                    Field("User", user) { user = it }
                    Field("Password", password, password = true) { password = it }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Anonymous", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = anonymous, onCheckedChange = { anonymous = it })
                }
                if (initial.protocol == NetProtocol.FTP || initial.protocol == NetProtocol.WEBDAV) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (initial.protocol == NetProtocol.FTP) "FTPS" else "HTTPS", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = tls, onCheckedChange = { tls = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
                // The honest note. Saying it here is cheaper than someone finding out later.
                Text(
                    when (initial.protocol) {
                        NetProtocol.FTP -> if (tls) "FTPS encrypts the connection."
                        else "Plain FTP sends your password in clear text over the network."
                        NetProtocol.SFTP -> "The host key is not pinned yet, so a hostile network could impersonate this server on the first connection."
                        NetProtocol.WEBDAV -> if (tls) "HTTPS encrypts the connection."
                        else "Plain HTTP sends your password in clear text over the network."
                        NetProtocol.SMB -> "SMB2 and SMB3 only. SMB1 is disabled everywhere for good reasons."
                    },
                    fontSize = 10.sp, color = colors.warn, lineHeight = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            label = label,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: initial.protocol.defaultPort,
                            user = user.trim(),
                            password = password,
                            share = share.trim(),
                            anonymous = anonymous,
                            useTls = tls,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label, fontSize = 11.sp) },
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}
