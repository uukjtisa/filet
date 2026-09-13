package dev.niccc2007.filet.nearby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.EmptyNote
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.SectionLabel
import dev.niccc2007.filet.browser.ago
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.settings.SmallButton
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Nearby: who is around, and what this device is offering.
 *
 * Two intents, two surfaces (NEARBY.md §3.1). This screen is the second one - *what has that
 * phone got* - because opening a peer in a pane is the interaction nothing else does. The
 * first intent, *send these files*, is the share sheet on a selection.
 */
@Composable
fun NearbyScreen(vm: BrowserViewModel) {
    val colors = Filet.colors
    val nearby = vm.nearby
    val server by nearby.state.collectAsState()
    val discovery by nearby.discovery.state.collectAsState()
    val shares by nearby.shared.entries.collectAsState()
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    /** Null when the code editor is closed; the draft code while it is open. */
    var editingPin by remember { mutableStateOf<String?>(null) }
    val grants by nearby.grants.all.collectAsState()

    DisposableEffect(Unit) {
        nearby.startScan()
        onDispose { nearby.stopScan() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Share over this network") }
        item { ShareCard(vm, server) { editingPin = nearby.fixedPin() ?: "" } }

        // Live endpoints. Everything that typed the code has a row here, and every row has a
        // Revoke that takes effect on the very next request - which is the whole reason a
        // grant is a URL and not an invisible cookie.
        if (grants.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "LET IN (${grants.size})",
                        fontSize = 10.sp, letterSpacing = 1.2.sp, color = colors.fg3,
                        modifier = Modifier.weight(1f),
                    )
                    SmallButton("Revoke all") { nearby.revokeAllGrants() }
                }
            }
            items(grants, key = { it.token }) { g ->
                GrantRow(
                    grant = g,
                    url = if (server.running) g.urlFor(server.address, server.port) else "",
                    onCopy = { vm.copyText(it, "Link copied") },
                    onRevoke = { nearby.revokeGrant(g.token) },
                )
            }
            item { GrantRules(nearby) }
        }

        if (server.running && server.clients.isNotEmpty()) {
            item { SectionLabel("Connected") }
            items(server.clients) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.good))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.address, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("${c.agent} · ${c.state}", fontSize = 10.sp, color = colors.fg3)
                    }
                    SmallButton("Kick") { nearby.kick(c.address) }
                }
            }
        }

        item { SectionLabel("Devices") }
        if (discovery.isolationSuspected) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.warn.copy(alpha = 0.15f))
                        .padding(11.dp),
                ) {
                    // Found on mDNS but refusing TCP is AP client isolation, not a bug. Say so.
                    Text(
                        "Your network blocks devices from talking to each other.",
                        fontSize = 12.sp, color = colors.warn, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Hotel, campus and many hotspot networks do this. A browser on the " +
                            "same network can still reach the URL above.",
                        fontSize = 10.5.sp, color = colors.fg2, lineHeight = 14.sp,
                    )
                }
            }
        }
        if (discovery.peers.isEmpty()) {
            item {
                EmptyNote(
                    if (discovery.scanning) "Looking for devices…"
                    else "No devices found yet.",
                    Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
        items(discovery.peers) { peer ->
            PeerRow(
                peer = peer,
                onOpen = { vm.openPeer(peer) },
                onPair = { pairing = nearby.beginPairing(peer) },
                onForget = { nearby.forget(peer.uuid) },
            )
        }

        item { SectionLabel("Shared (${shares.size} linked)") }
        item {
            Text(
                "Anything inside Filet/Shared is offered as-is. Files added with “Share” stay " +
                    "where they are and are streamed from there — a 4 GB video costs no extra space.",
                fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        items(shares) { e ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FiletIcons.Link, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.alias, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.path.path, fontSize = 9.5.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                }
                SmallButton("Revoke") { nearby.shared.revoke(e.token) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    pairing?.let { p ->
        PairingDialog(
            pairing = p,
            onConfirm = { nearby.confirmPairing(p); pairing = null; vm.toast("Paired with ${p.peer.label}") },
            onDismiss = { pairing = null },
        )
    }

    editingPin?.let { draft ->
        PinDialog(
            initial = draft,
            onClear = {
                nearby.setFixedPin(null)
                editingPin = null
                vm.toast("Back to a new code each session")
            },
            onDone = {
                nearby.setFixedPin(it)
                editingPin = null
                vm.toast("Access code set")
            },
            onDismiss = { editingPin = null },
        )
    }
}

/**
 * Choose a fixed access code, or go back to a fresh one per session.
 *
 * Random-per-session stays the default because it is the safer one. This exists because a
 * code that changes every time is genuinely tiresome when the same laptop connects ten times
 * a day, and that trade-off on a home network is the user's to make, not the app's to refuse.
 */
@Composable
private fun PinDialog(
    initial: String,
    onClear: () -> Unit,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.length == 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Access code", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    isError = value.isNotEmpty() && !valid,
                    label = { Text("Six digits") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave this and Filet picks a new code every time sharing starts, which " +
                        "is safer. A fixed code is easier to remember.",
                    fontSize = 11.sp,
                    color = Filet.colors.fg3,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(value) }, enabled = valid) { Text("Use this code") }
        },
        dismissButton = { TextButton(onClick = onClear) { Text("Random each time") } },
    )
}

@Composable
private fun GrantRow(
    grant: AccessGrant,
    url: String,
    onCopy: (String) -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(grant.label.ifBlank { "Browser" }, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                buildString {
                    append(grant.fromAddress)
                    append(" · let in ").append(ago(grant.createdAt))
                    when {
                        grant.expiresAt > 0 -> append(" · expires")
                        grant.idleRevokeMinutes > 0 -> append(" · idle-revoke ${grant.idleRevokeMinutes}m")
                        else -> append(" · until you revoke it")
                    }
                },
                fontSize = 10.sp, color = colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (url.isNotEmpty()) {
            SmallButton("Copy link") { onCopy(url) }
            Spacer(Modifier.width(5.dp))
        }
        SmallButton("Revoke", onClick = onRevoke)
    }
}

/**
 * When a granted endpoint should die.
 *
 * Default is **never**, and that is the user's call to reverse rather than the app's to
 * assume: an access that vanishes mid-download because a timer fired is a worse failure than
 * one that outlives its usefulness, and the list above makes an old grant visible rather than
 * forgotten.
 */
@Composable
private fun GrantRules(nearby: NearbyManager) {
    val colors = Filet.colors
    val lifetime = nearby.grants.defaultLifetimeMinutes
    val idle = nearby.grants.defaultIdleMinutes
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            "New links expire",
            fontSize = 11.sp, color = colors.fg2,
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(0 to "Never", 15 to "15m", 60 to "1h", 480 to "8h").forEach { (m, label) ->
                Chip(label, lifetime == m) { nearby.setGrantLifetimeMinutes(m) }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text("…or after no activity for", fontSize = 11.sp, color = colors.fg2)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(0 to "Never", 10 to "10m", 30 to "30m", 120 to "2h").forEach { (m, label) ->
                Chip(label, idle == m) { nearby.setGrantIdleMinutes(m) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Existing links keep the rule they were made with.",
            fontSize = 9.5.sp, color = colors.fg3,
        )
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 11.sp,
        color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
        modifier = Modifier
            .padding(end = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) colors.accent else colors.high)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun ShareCard(vm: BrowserViewModel, server: ServerState, onEditPin: () -> Unit) {
    val colors = Filet.colors
    val nearby = vm.nearby
    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(14.dp))
            .background(colors.raised)
            .padding(14.dp),
    ) {
        if (!server.running) {
            // Recomputed on every recomposition on purpose: the answer changes the moment
            // Wi-Fi comes up, and a stale "you have no network" is its own bug.
            val blocked = nearby.blockedReason()
            Text("Not sharing", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Start a share and anyone on this network can open the URL in a browser — no " +
                    "app, no account, no cable. Only works for devices on this network.",
                fontSize = 11.sp, color = colors.fg3, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (blocked == null) {
                SmallButton("Start sharing") { nearby.start() }
            } else {
                // R1, applied to a button: it cannot work right now, so it does not offer to.
                Text(
                    "Start sharing",
                    fontSize = 11.sp,
                    color = colors.fg3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.high)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(blocked, fontSize = 10.5.sp, color = colors.warn, lineHeight = 14.sp)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.Top) {
            QrCode(server.url, Modifier.size(132.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (server.pinRequired) {
                    Text("ACCESS CODE", fontSize = 9.sp, color = colors.fg3, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            server.pin.chunked(3).joinToString(" "),
                            fontSize = 21.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                            color = colors.accent,
                            modifier = Modifier.clickable { vm.copyText(server.pin, "Code copied") },
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton("Copy") { vm.copyText(server.pin, "Code copied") }
                        Spacer(Modifier.width(5.dp))
                        SmallButton("Set") { onEditPin() }
                    }
                    Spacer(Modifier.height(9.dp))
                }
                Text(
                    "Transfer is not encrypted on this network.",
                    fontSize = 10.sp, color = colors.warn, lineHeight = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // EVERY address, labelled.
        //
        // A phone can hold a Wi-Fi address, a hotspot address and a mobile address at once,
        // and only the person at the laptop knows which network it is on. Printing one guess
        // is what made "I typed the URL and nothing happened" the normal experience: the
        // mobile address enumerates first on this hardware and is behind carrier NAT.
        Text("OPEN ON ANOTHER DEVICE", fontSize = 9.sp, color = colors.fg3, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        if (server.addresses.isEmpty()) {
            Text("No network address right now.", fontSize = 11.sp, color = colors.warn)
        }
        for (addr in server.addresses) {
            val url = addr.urlFor(server.port)
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.copyText(url, "URL copied") }
                    .padding(vertical = 5.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    addr.kind.label,
                    fontSize = 9.sp,
                    color = if (addr.kind.reachable) colors.good else colors.warn,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            (if (addr.kind.reachable) colors.good else colors.warn)
                                .copy(alpha = 0.14f)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    url,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (addr.kind.reachable) MaterialTheme.colorScheme.onSurface else colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!addr.kind.reachable) {
                Text(
                    "Mobile data is behind your carrier's NAT — another device cannot reach this one.",
                    fontSize = 9.5.sp, color = colors.fg3, lineHeight = 12.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ToggleLine("Require a PIN", server.pinRequired) { nearby.setPinRequired(it) }
        ToggleLine("Allow uploads", server.uploadsAllowed) { nearby.setUploadsAllowed(it) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stop after idle", fontSize = 12.sp, modifier = Modifier.weight(1f))
            listOf(10, 30, 120).forEach { m ->
                val on = server.idleStopMinutes == m
                Text(
                    "${m}m",
                    fontSize = 11.sp,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
                    modifier = Modifier
                        .padding(start = 5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (on) colors.accent else colors.high)
                        .clickable { nearby.setIdleMinutes(m) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SmallButton("Stop sharing") { nearby.stop() }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PeerRow(peer: Peer, onOpen: () -> Unit, onPair: () -> Unit, onForget: () -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = if (peer.paired) onOpen else onPair)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(
                when {
                    !peer.reachable -> colors.bad
                    peer.paired -> colors.good
                    else -> colors.fg3
                }
            )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(if (peer.paired) "paired" else "new")
                    append(" · ")
                    // How it was found is the first diagnostic when something does not work.
                    append(
                        when (peer.foundBy) {
                            FoundBy.MDNS, FoundBy.PAIRED -> "Wi-Fi"
                            FoundBy.MANUAL -> "typed in"
                        }
                    )
                    append(" · ")
                    append(ago(peer.lastSeen))
                },
                fontSize = 10.sp, color = colors.fg3,
            )
        }
        Text(
            if (peer.paired) "Open" else "Pair",
            fontSize = 11.sp, color = colors.accent,
        )
        if (peer.paired) {
            Spacer(Modifier.width(10.dp))
            Icon(
                FiletIcons.Close, "Forget", tint = colors.fg3,
                modifier = Modifier.size(18.dp).clickable(onClick = onForget).padding(2.dp),
            )
        }
    }
}

/**
 * The short authentication string.
 *
 * Trust-on-first-use on a self-signed certificate is MITM-able on a hostile network, so the
 * first connection makes a person compare four digits. It is the cheapest real defence there
 * is, and skipping it is what makes most local-share apps only look secure.
 */
@Composable
private fun PairingDialog(pairing: Pairing, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = Filet.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with ${pairing.peer.label}", fontSize = 15.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Check that the same four digits appear on the other device.",
                    fontSize = 12.sp, color = colors.fg2,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    pairing.code,
                    fontSize = 34.sp,
                    letterSpacing = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.accent,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "If they differ, something is between the two devices. Do not continue.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) { Text("They match") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A QR of the share URL.
 *
 * Not decoration: typing `192.168.1.42:8321` on a phone keypad is the difference between
 * "share this" and "never mind".
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { Qr.encode(text) }
    val fg = MaterialTheme.colorScheme.onSurface
    Box(modifier.background(Color.White).padding(6.dp)) {
        Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
            if (matrix.isEmpty()) return@Canvas
            val n = matrix.size
            val cell = size.minDimension / n
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (!matrix[y][x]) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
