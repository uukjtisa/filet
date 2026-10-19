package dev.niccc2007.filet.desktop

import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The tool with no window: host, announce, listen, print, and stop cleanly on Ctrl+C.
 *
 * It exists so the hosting half is useful and testable before the window is, and so a machine
 * with no display can still serve. Everything here goes through [HostService] and [PeerWatch],
 * the same objects the window drives, so proving it headless proves the window's back end.
 *
 * Reached through the build's one entry point, `MainKt --headless`. A second top-level `main`
 * in this package would be a redeclaration of that one and the module would not compile.
 *
 *     --config FILE        use FILE instead of the per-OS config (for tests; the real one is untouched)
 *     --add FOLDER         share FOLDER, saved to the config; with --name NAME and --rw
 *     --remove FOLDER      stop sharing FOLDER
 *     --port N             preferred port, saved
 *     --list               print the config and exit
 */
object HostMain {

    fun run(args: Array<String>): Int {
        val a = args.toList()
        fun opt(k: String) = a.indexOf(k).takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }

        val store = opt("--config")?.let { ConfigStore(Paths.get(it)) } ?: ConfigStore()
        val mdns = Mdns()
        val host = HostService(store = store, mdns = mdns)

        opt("--port")?.toIntOrNull()?.let { p -> host.setPort(p)?.let { System.err.println(it); return 2 } }
        opt("--add")?.let { path ->
            host.addShare(path, writable = "--rw" in a, name = opt("--name"))?.let { System.err.println(it); return 2 }
        }
        opt("--remove")?.let { host.removeShare(it) }

        println("Filet Desktop - hosting, no window")
        println("  config    ${host.configFile.toAbsolutePath()}")
        println("  password  ${host.password}")
        for (s in host.shares.value) {
            println("  share     ${s.name}  ${s.path}  ${if (s.writable) "read+write" else "read only"}")
        }
        if ("--list" in a) { mdns.close(); return 0 }

        host.start()?.let { why -> System.err.println(why); mdns.close(); return 2 }

        val port = host.port.value
        if (port != host.preferredPort) {
            println("  NOTE      port ${host.preferredPort} was taken; listening on $port for this run")
        }
        println()
        printAddresses(host)
        for (m in host.missing.value) println("  NOT SERVED, folder missing: ${m.path}")
        println("Advertising ${PeerTxt.JMDNS_TYPE} with TXT path, scope, w, id")
        println("Basic WebDAV over plain HTTP: anyone on this network who has a code can reach that share.")
        println("Ctrl+C to stop.")
        println()

        val watch = PeerWatch(mdns, host.deviceId)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            watch.peers.drop(1).collect { list ->
                val before = host.chosenAddress.value
                host.notePeers(list)
                println("Peers on this network: ${list.size}")
                for (p in list) {
                    println("  ${p.name}  ${p.host}:${p.port}${p.base}  ${p.scope}${if (p.writable) "  read+write" else ""}")
                }
                // A peer on one subnet settles which of several addresses is the reachable one.
                if (host.chosenAddress.value != before) printAddresses(host)
            }
        }
        watch.start()

        val done = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "filet-shutdown") {
            // Withdraw before exit so phones drop the rows at once instead of at record expiry.
            println("Stopping: withdrawing announcements")
            runCatching { watch.stop() }
            runCatching { host.close() }
            runCatching { mdns.close() }
            scope.cancel()
            done.countDown()
        })
        done.await()
        return 0
    }
}

/**
 * The addresses as the phone would type them. One when it is known which adapter the phone can
 * reach; every site-local one, labelled with its adapter, when it is not.
 */
private fun printAddresses(host: HostService) {
    val chosen = host.chosenAddress.value
    val all = host.addresses.value
    if (chosen != null || all.size <= 1) {
        println("Serving at ${host.address.value ?: host.endpoints.value.firstOrNull()?.url}")
        for (e in host.endpoints.value) {
            println("  ${e.name.padEnd(16)} ${if (e.writable) "read+write" else "read only "}  ${e.url}")
        }
        return
    }
    println("This PC has ${all.size} network addresses and it is not yet known which one the phone can reach.")
    println("Use the one on the same network as the phone:")
    for (e in host.endpoints.value) {
        println("  ${e.name}  ${if (e.writable) "read+write" else "read only"}")
        for (u in e.urls) println("    ${u.adapter.take(32).padEnd(32)}  ${u.url}")
    }
}
