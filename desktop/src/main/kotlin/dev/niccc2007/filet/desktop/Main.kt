package dev.niccc2007.filet.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.niccc2007.filet.desktop.mount.LinuxMounts
import dev.niccc2007.filet.desktop.mount.MountResult
import dev.niccc2007.filet.desktop.mount.Mounters
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.mount.ProbeResult
import dev.niccc2007.filet.desktop.ui.FiletWindow
import dev.niccc2007.filet.desktop.ui.MountController
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
fun main(args: Array<String>) {
    if ("--headless" in args) exitProcess(HostMain.run(args))
    mountCommand(args)?.let { exitProcess(it) }

    val mdns = Mdns()
    val host = HostService(mdns = mdns)
    val watch = PeerWatch(mdns, host.deviceId)
    watch.start()
    val mounter = Mounters.forThisOs()

    application {
        val state = rememberWindowState(size = DpSize(620.dp, 760.dp), isMinimized = Autostart.FLAG in args)
        val scope = rememberCoroutineScope()
        val mounts = remember { MountController(mounter, watch.peers, scope).also { it.start() } }
        // The host picks its address from the subnet a discovered peer is on, so it has to hear
        // every change to the peer list - without this it has nothing to choose with.
        androidx.compose.runtime.LaunchedEffect(Unit) { watch.peers.collect { host.notePeers(it) } }
        Window(
            onCloseRequest = {
                // Goodbye packets before exit, so phones drop this PC's rows now rather than at TTL.
                host.close()
                watch.stop()
                mdns.close()
                exitApplication()
            },
            title = "Filet Desktop",
            state = state,
        ) {
            FiletWindow(host, watch.peers, mounts, rescan = { watch.rescan() })
        }
    }
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
