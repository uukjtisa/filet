package dev.niccc2007.filet.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.niccc2007.filet.desktop.mount.LinuxMounts
import dev.niccc2007.filet.desktop.mount.MountResult
import dev.niccc2007.filet.desktop.mount.Mounters
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.mount.ProbeResult
import dev.niccc2007.filet.desktop.ui.FiletWindow
import dev.niccc2007.filet.desktop.ui.MountController
import dev.niccc2007.filet.desktop.update.Updater
import kotlin.system.exitProcess

/**
 * Entry point, named by `mainClass` in the build.
 *
 * `application {}` runs its composition on Swing's event thread - it is `awaitApplication` inside
 * `Dispatchers.Swing` - which is the thread Compose Desktop must draw on, and why the build carries
 * `kotlinx-coroutines-swing`: `Dispatchers.Main` here IS that thread.
 *
 * Two non-window modes, both for running without a screen:
 *  - `--headless`: sharing only, owned by [HostMain].
 *  - `--mounts`, `--mount <url> [label]`, `--unmount <where>`: the exact mount path the window
 *    uses, driven from a terminal. Peer discovery hides this machine's own share from itself, so
 *    this is the way to prove mounting against a share served on the same PC.
 */
/**
 * Whether this launch starts sharing: when the last run was sharing and nobody pressed Stop, or
 * when the updater reopens the app with `--share` after installing. Pure.
 *
 * Decision: resume rather than wait for Start. "Start with this computer" exists so a phone can
 * reach the PC after a reboot, and a tool that opens at login with sharing off does not do that.
 */
internal fun sharesAtLaunch(args: Array<String>, wasSharing: Boolean): Boolean = wasSharing || "--share" in args

fun main(args: Array<String>) {
    if ("--headless" in args) exitProcess(HostMain.run(args))
    mountCommand(args)?.let { exitProcess(it) }

    val mdns = Mdns()
    val host = HostService(mdns = mdns)
    val watch = PeerWatch(mdns, host.deviceId)
    watch.start()
    val mounter = Mounters.forThisOs()
    // An install that moved leaves a login entry pointing at the old path; point it here.
    runCatching { Autostart.repair() }
    if (sharesAtLaunch(args, host.wasSharing)) host.start()?.let { System.err.println(it) }

    // The process outlives the window by the shutdown below, so the application must not end it.
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(
            size = fitted(DpSize(1100.dp, 860.dp)),
            position = WindowPosition(Alignment.Center),
            isMinimized = Autostart.FLAG in args,
        )
        val scope = rememberCoroutineScope()
        val mounts = remember {
            MountController(mounter, watch.peers, scope, loadPhones = { host.phones }, savePhones = host::savePhones, selfId = host.deviceId).also { it.start() }
        }
        // The host picks its address from the subnet a discovered peer is on, so it has to hear
        // every change to the peer list - without this it has nothing to choose with.
        androidx.compose.runtime.LaunchedEffect(Unit) { watch.peers.collect { host.notePeers(it) } }
        val updater = remember { Updater(scope).also { it.noteArgs(args) } }
        // A first look shortly after opening, then twice a day for a window left open for days.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(8_000)
            while (true) {
                updater.check(manual = false)
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
            }
        }
        Window(
            // The window goes at once. Stopping the server and the goodbyes happen after it, below.
            onCloseRequest = ::exitApplication,
            title = "Filet Desktop Network Tool",
            // The phone's folder mark, so the window and the taskbar say Filet rather than Java.
            // Rasterised from desktop/icons/filet.svg by make_icons.py there.
            icon = androidx.compose.ui.res.painterResource("filet.png"),
            state = state,
        ) {
            // Small enough for a half-screen window; below the two-column width the window stacks.
            androidx.compose.runtime.LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(640, 480) }
            FiletWindow(
                host, watch.peers, mounts, rescan = { watch.rescan() },
                updater = updater,
                install = { offer -> updater.install(offer, share = host.running.value, quit = ::exitApplication) },
            )
        }
    }
    shutdown({ host.close() }, { watch.stop() }, { mdns.close() })
    exitProcess(0)
}

/**
 * Closing, after the window is gone: goodbye packets so phones drop this PC's rows now rather
 * than at TTL, then the server and discovery stopped.
 *
 * Bug identified here: all of this ran inside the close handler, on the thread that draws the
 * window, so the window sat frozen for the goodbye wait plus mDNS closing one responder per network
 * adapter - several seconds, read as a hang. Now nothing is on screen while it runs, and it is
 * capped: a goodbye that cannot leave in a few seconds is not worth a process left behind.
 */
private fun shutdown(vararg steps: () -> Unit) {
    val t = Thread({ for (s in steps) runCatching { s() } }, "filet-shutdown").apply { isDaemon = true }
    t.start()
    t.join(4_000)
}

/**
 * [want], shrunk to the screen's usable area less a margin, so the window never opens with its
 * bottom under the taskbar. AWT reports that area in the same units Compose sizes windows in.
 */
private fun fitted(want: DpSize): DpSize {
    val area = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        ?: return want
    return DpSize(minOf(want.width, (area.width - 40).dp), minOf(want.height, (area.height - 40).dp))
}

/** Null when [args] is not a mount command; otherwise runs it and returns the exit status. */
private fun mountCommand(args: Array<String>): Int? {
    val m = Mounters.forThisOs()
    fun result(r: MountResult): Int = when (r) {
        is MountResult.Ok -> { println("OK ${r.where}"); 0 }
        is MountResult.Failed -> { println("FAILED${r.code?.let { " ($it)" } ?: ""}: ${r.message}"); 1 }
    }
    return when (args.firstOrNull()) {
        "--mounts" -> {
            m.unavailable()?.let { println("UNAVAILABLE: $it") }
            for (a in m.attached()) {
                val probe = Probe.check(a.target)
                val space = (probe as? ProbeResult.Ok)?.space?.let { Probe.describe(it) } ?: "-"
                println("${a.where}\t${a.target.url}\tconnected=${a.connected}\tanswering=${probe is ProbeResult.Ok}\t$space")
            }
            0
        }
        "--mount" -> {
            val target = args.getOrNull(1)?.let { LinuxMounts.parseUrl(it) }
                ?: run { println("usage: --mount http://host:port/a/CODE/ [label]"); return 2 }
            when (val p = Probe.check(target)) {
                is ProbeResult.Refused -> { println("FAILED (HTTP ${p.status}): ${p.message}"); return 1 }
                ProbeResult.Unreachable -> { println("FAILED: unreachable"); return 1 }
                is ProbeResult.Ok -> p.space?.let { println("share reports ${Probe.describe(it)}") }
            }
            result(m.mount(target, args.getOrNull(2) ?: target.host))
        }
        "--unmount" -> {
            val where = args.getOrNull(1) ?: run { println("usage: --unmount Z:"); return 2 }
            val a = m.attached().firstOrNull { it.where.equals(where, ignoreCase = true) }
                ?: run { println("OK $where (was not mounted)"); return 0 }
            result(m.unmount(a, force = "--force" in args))
        }
        else -> null
    }
}
