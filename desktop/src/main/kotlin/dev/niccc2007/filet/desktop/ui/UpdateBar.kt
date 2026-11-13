package dev.niccc2007.filet.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.update.Offer
import dev.niccc2007.filet.desktop.update.UpdateState
import dev.niccc2007.filet.desktop.update.Updater
import dev.niccc2007.filet.desktop.update.Version

/**
 * The update line under the status strip: shown only when there is something to say.
 *
 * An update is offered, never forced: the window says what is out, links what changed, and
 * installs only on Update now. A jar run cannot replace an installer it does not have, so there
 * the button opens the release instead.
 */
@Composable
fun UpdateBar(updater: Updater, install: (Offer) -> Unit) {
    val state by updater.state.collectAsState()
    val notice by updater.notice.collectAsState()
    // Later hides the offer for this run; the strip still says it is there.
    var later by remember { mutableStateOf<String?>(null) }

    notice?.let { n ->
        Bar(if (n.startsWith("Updated")) Ink.ok else Ink.warn) {
            Txt(n, 13, if (n.startsWith("Updated")) Ink.ok else Ink.warn, modifier = Modifier.weight(1f))
            Btn("OK") { updater.dismissNotice() }
        }
    }
    when (val s = state) {
        is UpdateState.Available -> if (later != s.offer.version) Bar(Ink.accent) {
            Txt("Filet Desktop Network Tool ${s.offer.version} is out. This is ${Version.current}.", 13, Ink.text, bold = true, modifier = Modifier.weight(1f))
            if (s.offer.page.isNotBlank()) Btn("What's new", kind = BtnKind.GHOST) { browse(s.offer.page) }
            Btn("Later", kind = BtnKind.GHOST) { later = s.offer.version }
            if (updater.canInstall) Btn("Update now", primary = true) { install(s.offer) }
            else Btn("Open the release", primary = true) { browse(s.offer.page) }
        }
        is UpdateState.Downloading -> Bar(Ink.accent) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val pct = if (s.total > 0) (s.done * 100 / s.total).toInt() else null
                Txt("Downloading ${s.offer.version}" + (pct?.let { " · $it%" } ?: " · ${Probe.size(s.done)}"), 13)
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF22302F), RoundedCornerShape(2.dp))) {
                    Box(
                        Modifier.fillMaxWidth(if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f).height(4.dp)
                            .background(Brush.horizontalGradient(listOf(Ink.primary, Ink.accent)), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        is UpdateState.Installing -> Bar(Ink.accent) {
            Txt("Installing ${s.offer.version}. The window closes and opens again in a few seconds.", 13, modifier = Modifier.weight(1f))
        }
        is UpdateState.Failed -> Bar(Ink.warn) {
            Txt(s.message, 13, Ink.warn, modifier = Modifier.weight(1f))
            Btn("Try again") { s.offer?.let(install) ?: updater.check(manual = true) }
            Btn("OK", kind = BtnKind.GHOST) { updater.reset() }
        }
        UpdateState.Current -> Bar(Ink.line) {
            Txt("Filet Desktop Network Tool ${Version.current} is the newest.", 13, Ink.soft, modifier = Modifier.weight(1f))
            Btn("OK", kind = BtnKind.GHOST) { updater.reset() }
        }
        else -> Unit
    }
}

/** The version, and a way to check now. Sits in the status strip. */
@Composable
fun VersionChip(updater: Updater) {
    val state by updater.state.collectAsState()
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val ready = (state as? UpdateState.Available)?.offer?.version
        Txt(if (ready != null) "Filet Desktop Network Tool ${Version.current} · $ready is out" else "Filet Desktop Network Tool ${Version.current}", 11.5, if (ready != null) Ink.accent else Ink.muted)
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
        Btn(if (state is UpdateState.Checking) "Checking..." else "Check for updates", enabled = !busy, kind = BtnKind.GHOST) { updater.check(manual = true) }
    }
}

@Composable
private fun Bar(edge: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.panel).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(end = 4.dp).height(22.dp).background(edge, RoundedCornerShape(2.dp)).padding(start = 3.dp))
        content()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
}

/** Off the window's thread: the browser can take a moment to answer. */
private fun browse(url: String) {
    Thread { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }.apply { isDaemon = true }.start()
}
