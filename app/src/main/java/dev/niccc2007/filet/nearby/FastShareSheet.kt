package dev.niccc2007.filet.nearby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgCaption
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.dialogs.DlgTone
import dev.niccc2007.filet.ui.dialogs.DlgWarn
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fast share: one sheet with both ways. The QR on top, a direct link on this phone's own address
 * that any camera downloads at once and Filet's own scanner fetches in Filet; the Filets on this
 * network under it, each a tap to push to. Opening it starts sharing when sharing is off.
 *
 * A locked file asks first, because the key the QR carries opens it without the lock code.
 */
@Composable
fun FastShareSheet(vm: BrowserViewModel, nodes: List<VNode>, onClose: () -> Unit) {
    val nearby = vm.nearby
    val entries = nearby.shared.entries.value
    val locked = remember(nodes) {
        nodes.any { n -> entries.any { e -> e.locked && (e.path == n.path || e.path.contains(n.path)) } }
    }
    var go by remember(nodes) { mutableStateOf(!locked) }
    if (!go) {
        Dlg(onClose) {
            DlgHeader(FiletIcons.Lock, "Fast share", tone = DlgTone.WARN, onClose = onClose)
            DlgBody { DlgWarn("You enabled restricted access for this. Fast share bypasses it.") }
            DlgFooter {
                DlgBtn("Cancel", onClick = onClose)
                DlgBtn("Continue", BtnKind.PRIMARY) { go = true }
            }
        }
        return
    }

    val fast = remember(nodes) { nearby.fastShare(nodes) }
    val server by nearby.state.collectAsState()
    val discovery by nearby.discovery.state.collectAsState()
    val link = remember(fast, server.running, server.address, server.port) { nearby.fastLink(fast) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Each reply is kept with the address it came from: a phone looked up again at a new address
    // starts over at "Tap to send" rather than wearing the old address's failure.
    val answers = remember(fast) { mutableStateMapOf<String, Pair<String, String>>() }
    val seenAll by nearby.fastSeen.collectAsState()
    val seen = seenAll[fast.key].orEmpty()
    DisposableEffect(Unit) {
        if (LocalNetwork.allowed(context)) nearby.startScan()
        onDispose { nearby.stopScan() }
    }
    val colors = Filet.colors
    val title = nodes.singleOrNull()?.name ?: "${nodes.size} files"

    Dlg(onClose) {
        DlgHeader(FiletIcons.Share, "Fast share", sub = title, onClose = onClose)
        DlgBody {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (link.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.size(196.dp)) {
                        CircularProgressIndicator(Modifier.size(26.dp), color = colors.accent, strokeWidth = 2.5.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Starting the share…", fontSize = 12.sp, color = colors.fg2)
                    }
                } else {
                    dev.niccc2007.filet.qr.FiletQr(link, Modifier.size(196.dp))
                }
            }
            if (link.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    link, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            DlgCaption(
                (if (nodes.size > 1) "A browser gets one zip; Filet gets the separate files. " else "") +
                    "Same Wi-Fi only. Any camera downloads it at once, with no page and no code; " +
                    "Filet's own scanner saves it to Filet/Received. ${stopsIn(fast.expires, System.currentTimeMillis())?.replaceFirstChar { it.uppercase() } ?: ""}.",
            )
            DlgSection("Filets on this network")
            val peers = discovery.peers
            if (peers.isEmpty()) {
                DlgCaption(
                    if (!LocalNetwork.allowed(context)) "Allow Filet to use this network on the Share page to find Filets nearby."
                    else if (discovery.scanning) "Looking for Filets…" else "No Filets found yet. They show up while Filet is open on them.",
                )
            }
            for (p in peers) {
                val said = answers[p.uuid]?.takeIf { it.first == p.base }?.second
                val heard = seen[NetHost.plain(p.host)]
                DlgPick(
                    FiletIcons.Device,
                    p.label,
                    detail = PushRow.detail(said, heard, p.paired),
                    selected = PushRow.answered(said, heard),
                ) {
                    if (link.isEmpty() || said == PushReply.SENDING) return@DlgPick
                    val base = p.base
                    answers[p.uuid] = base to PushReply.SENDING
                    scope.launch { answers[p.uuid] = base to (nearby.push(p, fast) ?: "none") }
                }
            }
        }
        DlgFooter {
            DlgBtn("Stop fast share", fill = false) {
                nearby.stopFast(fast.key)
                vm.toast("Fast share stopped: its QR and link no longer open anything")
                onClose()
            }
            DlgBtn("Copy link", enabled = link.isNotEmpty()) { vm.copyText(link, "Link copied") }
            DlgBtn("Done", BtnKind.PRIMARY, onClick = onClose)
        }
    }
}

/**
 * Another Filet's push: Accept, Decline, and Always accept from this phone, offered only for a
 * paired phone. Closing it without an answer leaves the push in the Devices list.
 */
@Composable
fun PushAsk(vm: BrowserViewModel, offer: PushOffer) {
    val inbox = vm.nearby.inbox
    var always by remember(offer.id) { mutableStateOf(false) }
    Dlg({ inbox.later() }) {
        DlgHeader(
            FiletIcons.Download,
            "${offer.name} wants to send you " + if (offer.files.size == 1) "a file" else "${offer.files.size} files",
            sub = "A Filet on this network",
            onClose = { inbox.later() },
        )
        DlgBody {
            OfferedFiles(offer.files)
            DlgTick(
                on = always && offer.paired,
                label = "Always accept from this phone",
                sub = if (offer.paired) "Next time it arrives without asking. Undo on the Share page."
                else "Pair with this phone on the Share page first.",
                enabled = offer.paired,
            ) { always = !always }
        }
        DlgFooter {
            DlgBtn("Decline") { inbox.decline(offer) }
            DlgBtn("Accept", BtnKind.PRIMARY) { inbox.accept(offer, always) }
        }
    }
}

/** The files a push or a scanned share offers: the first six, and where they will land. */
@Composable
private fun OfferedFiles(files: List<PushFile>) {
    for (f in files.take(6)) {
        DlgPick(
            if (f.dir) FiletIcons.Folder else dev.niccc2007.filet.browser.FileKind.of(
                VNode(dev.niccc2007.filet.vfs.VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0),
            ).icon,
            f.name + if (f.dir) ".zip" else "",
            detail = if (f.size >= 0 && !f.dir) dev.niccc2007.filet.browser.humanSize(f.size) else null,
        ) {}
    }
    if (files.size > 6) DlgCaption("and ${files.size - 6} more")
    DlgCaption(if (files.size == 1) "It goes to Filet/Received." else "They go to Filet/Received.")
}

/**
 * A fast share's QR, read by Filet's scanner: what it holds, and Download or Cancel. Nothing is
 * fetched until Download - a code read by pointing a camera is not a decision to take its files,
 * any more than a push is, and a push asks.
 */
@Composable
fun ScannedShareAsk(vm: BrowserViewModel, link: FastLink.Parsed, onClose: () -> Unit) {
    val inbox = vm.nearby.inbox
    // Unknown until the sharing phone answers; then what it holds, or null when it did not answer.
    var asked by remember(link) { mutableStateOf(false) }
    var peek by remember(link) { mutableStateOf<FastPeek?>(null) }
    LaunchedEffect(link) {
        peek = withContext(Dispatchers.IO) { inbox.peek(link.base, link.key) }
        asked = true
    }
    val p = peek
    val who = p?.device?.ifEmpty { null } ?: link.host
    Dlg(onClose) {
        DlgHeader(
            FiletIcons.Download,
            when {
                p != null -> "$who is sharing " + if (p.files.size == 1) "a file" else "${p.files.size} files"
                asked -> "This share has stopped"
                else -> "A fast share"
            },
            sub = "A Filet on this network",
            onClose = onClose,
        )
        DlgBody {
            when {
                p != null -> OfferedFiles(p.files)
                asked -> DlgCaption("$who is not sharing it any more, or this phone is on a different Wi-Fi.")
                else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
        DlgFooter {
            if (p == null) {
                DlgBtn(if (asked) "Close" else "Cancel", onClick = onClose)
            } else {
                DlgBtn("Cancel", onClick = onClose)
                DlgBtn("Download", BtnKind.PRIMARY) {
                    vm.toast(
                        if (inbox.fetch(link.base, link.key, who)) "Downloading to Filet/Received"
                        else "Already downloading this one",
                    )
                    onClose()
                }
            }
        }
    }
}

/** Another Filet asking to pair: the same four digits show on both phones. */
@Composable
fun PairAskDialog(vm: BrowserViewModel, ask: PairAsk) {
    val inbox = vm.nearby.inbox
    Dlg({ inbox.answerPair(ask, false) }) {
        DlgHeader(FiletIcons.Device, "Pair with ${ask.name}?", sub = "Check that both phones show the same code", onClose = { inbox.answerPair(ask, false) })
        DlgBody {
            Text(
                ask.code, fontSize = 30.sp, fontFamily = FontFamily.Monospace, color = Filet.colors.accent,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            DlgCaption("A paired phone can be set to Always accept, and no other phone can borrow its name to do it.")
        }
        DlgFooter {
            DlgBtn("Not this one") { inbox.answerPair(ask, false) }
            DlgBtn("Pair", BtnKind.PRIMARY) {
                inbox.answerPair(ask, true)
                vm.toast("Paired with ${ask.name}")
            }
        }
    }
}

/** The share's pop-ups, wherever Filet is: a push asking, another Filet asking to pair. */
@Composable
fun NearbyPopups(vm: BrowserViewModel) {
    val inbox = vm.nearby.inbox
    val asking by inbox.asking.collectAsState()
    val pairing by inbox.pairing.collectAsState()
    pairing?.let { PairAskDialog(vm, it) } ?: asking?.let { PushAsk(vm, it) }
}
