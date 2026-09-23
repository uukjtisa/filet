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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.tabs.ChipRow
import dev.niccc2007.filet.ui.tabs.DotState
import dev.niccc2007.filet.ui.tabs.MicroLabel
import dev.niccc2007.filet.ui.tabs.NLead
import dev.niccc2007.filet.ui.tabs.NRow
import dev.niccc2007.filet.ui.tabs.SmallBtn
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.tabs.ToggleRow
import dev.niccc2007.filet.ui.tabs.WarnNote
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.webdav.DavScope
import dev.niccc2007.filet.webdav.WebDavServer

/**
 * Mount this phone on a computer.
 *
 * The other half of the Remotes tab. Everything above it on that screen connects Filet TO
 * something; this one makes Filet the thing being connected to, which is why it is here and
 * not on Nearby - Nearby hands a browser a page, this hands Explorer a drive letter.
 */
@Composable
fun HostingCard(vm: BrowserViewModel) {
    val colors = Filet.colors
    val dav by vm.davState.collectAsState()
    // Walked once per state change rather than on every recomposition: enumerating interfaces
    // is a syscall per interface and this card redraws on every request that arrives.
    val addresses = remember(dav.running, dav.port) {
        if (dav.running) vm.hostAddresses() else emptyList()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
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
                        !dav.running -> "Appear in Windows Explorer as a drive"
                        dav.clients.isEmpty() -> "nothing mounted yet · ${dav.scope.label}"
                        dav.clients.size == 1 -> "1 computer mounted · ${dav.scope.label}"
                        else -> "${dav.clients.size} computers mounted · ${dav.scope.label}"
                    },
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))

        Column(Modifier.padding(13.dp)) {
            if (!dav.running) {
                Text(
                    "WebDAV is a protocol, not a service. This phone is the server, nothing " +
                        "is hosted for you and there is no account anywhere — it costs nothing " +
                        "because there is nothing to buy.",
                    fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.7.sp,
                )
                Spacer(Modifier.height(11.dp))
                ScopeControls(vm, dav.scope, dav.writable, dav.idleStopMinutes)
                Spacer(Modifier.height(9.dp))
                TabButton("Start hosting", FiletIcons.Wifi, primary = true) { vm.startHosting() }
                return@Column
            }

            MicroLabel("Map this in Windows Explorer", Modifier.padding(bottom = 4.dp))
            for (addr in addresses.filter { it.kind.reachable }) {
                // The UNC form, not the URL. Explorer will not accept an http:// address in
                // its address bar for a drive mapping - the @port and DavWWWRoot spelling is
                // what makes Windows route it to its WebDAV client instead of to a web fetch.
                UncRow(
                    kind = addr.kind.label,
                    text = "\\\\${addr.ip}@${dav.port}\\DavWWWRoot\\a\\${dav.code}",
                    onCopy = { vm.copyText(it, "Address copied") },
                )
            }
            Text(
                "Paste it into the address bar, or use Map network drive to give it a letter. " +
                    "The code in the link is the password — there is no sign-in box.",
                fontSize = 9.5.sp, color = colors.fg3, lineHeight = 13.sp,
            )

            Spacer(Modifier.height(10.dp))
            MicroLabel("Mac and Linux", Modifier.padding(bottom = 4.dp))
            for (addr in addresses.filter { it.kind.reachable }) {
                UncRow(
                    kind = addr.kind.label,
                    text = "http://${addr.ip}:${dav.port}/a/${dav.code}",
                    onCopy = { vm.copyText(it, "Address copied") },
                )
            }
            if (addresses.none { it.kind.reachable }) {
                Text(
                    "No network address right now. Join a Wi-Fi network or turn on a hotspot.",
                    fontSize = 11.sp, color = colors.warn,
                )
            }

            Spacer(Modifier.height(10.dp))
            ScopeControls(vm, dav.scope, dav.writable, dav.idleStopMinutes)

            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabButton("Stop hosting", FiletIcons.Close) { vm.stopHosting() }
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (dav.writable) "read and write" else "read only") + " · ${dav.scope.label}",
                    fontSize = 10.sp,
                    color = colors.fg3,
                )
            }
        }
    }

    // Who has it mounted. One row each, with a way to cut it off.
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
        Column(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
            // All three of these are load-bearing. Each one is the only explanation for
            // something that otherwise looks exactly like a broken file or a broken app.
            WarnNote(
                "Windows stops WebDAV downloads at 50 MB until its registry limit is raised. " +
                    "Reading a large file straight off the drive fails at exactly that size, " +
                    "which looks like a broken file and is not one. Copy it with a browser " +
                    "link instead, or raise FileSizeLimitInBytes."
            )
            WarnNote(
                "Windows needs its WebClient service running. It is on by default; if the " +
                    "address will not open at all, that is the first thing to check."
            )
            WarnNote(
                "Nothing leaves your network and nothing is hosted for you. This phone is the " +
                    "server, and there is no account anywhere."
            )
        }
    }
}

@Composable
private fun ScopeControls(
    vm: BrowserViewModel,
    scope: DavScope,
    writable: Boolean,
    idleMinutes: Int,
) {
    ChipRow(
        "Let the PC see",
        DavScope.entries.map { it.label },
        scope.label,
    ) { picked -> vm.setHostScope(DavScope.entries.first { it.label == picked }) }
    // Off by default and not persisted. Turning a phone into a writable network drive is a
    // thing to decide each time, not a setting to forget having left on.
    ToggleRow("Let the PC change files", writable) { vm.setHostWritable(it) }
    ChipRow(
        "Stop after idle",
        IDLE_CHOICES.map { "${it}m" },
        "${idleMinutes}m",
    ) { picked -> vm.setHostIdleMinutes(picked.removeSuffix("m").toIntOrNull() ?: 30) }
}

/**
 * One address, wrapped rather than ellipsised.
 *
 * A UNC path is a string somebody retypes into another machine. Truncating the middle of it
 * with an ellipsis is fine for a label you tap and useless for one you have to read out.
 */
@Composable
private fun UncRow(kind: String, text: String, onCopy: (String) -> Unit) {
    val colors = Filet.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCopy(text) }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            kind,
            fontSize = 9.sp,
            color = colors.good,
            modifier = Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.good.copy(alpha = 0.14f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 15.4.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            FiletIcons.Copy, "Copy", tint = colors.fg3,
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
    }
}

/** The idle windows offered. Never "never" - a forgotten open socket is the thing to avoid. */
private val IDLE_CHOICES = listOf(10, 30, 120)

/** So the card and the server cannot disagree about the default port. */
@Suppress("unused")
private val DEFAULT_PORT = WebDavServer.DEFAULT_PORT
