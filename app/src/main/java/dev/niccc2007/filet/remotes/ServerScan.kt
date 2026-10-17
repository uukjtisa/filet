package dev.niccc2007.filet.remotes

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finding servers on this network that are not Filet.
 *
 * ## Why this is not the beacon
 *
 * Filet already discovers other Filets, over its own mDNS advertisement, and that is the whole
 * of what the Remotes screen could find. But the thing most people are trying to reach from a
 * phone is a Windows PC, a NAS, or a Raspberry Pi running Samba - none of which advertise
 * anything Filet publishes, and all of which were therefore invisible. Adding one meant
 * knowing its IP address, which is the question somebody comes to this screen unable to answer.
 *
 * ## Two ways of looking, because neither finds everything
 *
 * **mDNS** is the polite way: a NAS, a Mac and most Linux boxes announce `_smb._tcp`,
 * `_sftp-ssh._tcp` or `_webdav._tcp`, and asking costs one multicast packet. A Windows PC
 * announces none of them - Windows uses WS-Discovery and NetBIOS instead - so mDNS alone
 * misses the single most common case.
 *
 * **A port sweep** finds the rest: 254 addresses, one short connect each. It is the only thing
 * that finds a Windows machine, and it is deliberately narrow about what it looks for - see
 * [PORTS]. This is not a port scanner: it checks four ports that correspond to four things this
 * app can actually mount, on the local subnet only, and it reports them to the person holding
 * the phone.
 *
 * ## What it will not do
 *
 * It does not try to log in. A service answering on 445 proves something is there; whether
 * these credentials work is what the Test button is for, and a scan that tried guest
 * credentials against every host on a network would be doing something nobody asked for.
 *
 * It does not leave the subnet. The sweep is built from this device's own interface address and
 * its prefix length, and it refuses anything that is not a /24 or smaller - see [sweepFor].
 * Walking a /16 would be 65,000 connects, which is both useless and indistinguishable from an
 * attack.
 */
class ServerScan(private val context: Context, private val scope: CoroutineScope) {

    /**
     * @param host the address to connect to.
     * @param name what to call it, when something said.
     * @param protocol what Filet would mount it as.
     * @param port the port it answered on.
     * @param byName true when it announced itself, false when it was swept up.
     */
    data class Found(
        val host: String,
        val name: String,
        val protocol: NetProtocol,
        val port: Int,
        val byName: Boolean,
    ) {
        /** One row per address and protocol: a NAS answers on several and each is a choice. */
        val key: String get() = "$host/$protocol"
    }

    data class State(
        val found: List<Found> = emptyList(),
        val sweeping: Boolean = false,
        /** How far through the subnet, 0 to 1, for a progress line that is honest. */
        val progress: Float = 0f,
        val note: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var sweepJob: Job? = null
    private val listeners = ArrayList<Pair<String, NsdManager.DiscoveryListener>>()

    private val nsd: NsdManager?
        get() = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()

    /** Ask the network to introduce itself. Cheap, and it finds the polite half. */
    fun listen() {
        val manager = nsd ?: return
        if (listeners.isNotEmpty()) return
        for ((type, protocol) in SERVICES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) = Unit
                override fun onDiscoveryStopped(t: String) = Unit
                override fun onStartDiscoveryFailed(t: String, code: Int) = Unit
                override fun onStopDiscoveryFailed(t: String, code: Int) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    runCatching {
                        manager.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, code: Int) = Unit
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val host = resolved.host?.hostAddress ?: return
                                    add(
                                        Found(
                                            host = host,
                                            name = resolved.serviceName.ifBlank { host },
                                            protocol = protocol,
                                            port = resolved.port.takeIf { it > 0 }
                                                ?: protocol.defaultPort,
                                            byName = true,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            runCatching {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(type to listener)
            }
        }
    }

    fun stop() {
        val manager = nsd
        for ((_, listener) in listeners) {
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        listeners.clear()
        sweepJob?.cancel()
        sweepJob = null
        _state.value = _state.value.copy(sweeping = false, progress = 0f)
    }

    /**
     * Walk the subnet looking for the four things Filet can mount.
     *
     * Deliberately a button rather than something that happens on arrival: it is 254 connects,
     * it takes a few seconds, and a screen that does it unasked every time it opens is a screen
     * that wakes the Wi-Fi radio for nothing most of the time.
     */
    fun sweep() {
        if (sweepJob?.isActive == true) return
        val plan = sweepFor(localAddresses())
        if (plan == null) {
            _state.value = _state.value.copy(
                note = "Filet could not work out this network's shape. A hotspot or a VPN " +
                    "often has no ordinary subnet to walk.",
            )
            return
        }
        _state.value = _state.value.copy(sweeping = true, progress = 0f, note = null)
        sweepJob = scope.launch {
            // Bounded concurrency. Unbounded, this is 254 x 4 sockets at once, which on a
            // phone exhausts the file-descriptor limit and starts failing connects that would
            // otherwise have succeeded - so the scan would report fewer servers the better the
            // network was.
            val gate = Semaphore(PARALLEL)
            var done = 0
            val total = plan.size * PORTS.size
            withContext(Dispatchers.IO) {
                for (host in plan) {
                    for ((port, protocol) in PORTS) {
                        launch {
                            gate.withPermit {
                                if (reachable(host, port)) {
                                    add(
                                        Found(
                                            host = host,
                                            name = host,
                                            protocol = protocol,
                                            port = port,
                                            byName = false,
                                        ),
                                    )
                                }
                            }
                            done++
                            _state.value = _state.value.copy(
                                progress = (done.toFloat() / total).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            _state.value = _state.value.copy(sweeping = false, progress = 1f)
        }
    }

    private fun add(found: Found) {
        val existing = _state.value.found
        // A host that ANNOUNCED itself replaces the swept-up row for the same service: it
        // carries a name, and a name is the whole reason somebody wanted discovery.
        val without = existing.filterNot { it.key == found.key && (!it.byName || found.byName) }
        if (without.size == existing.size && existing.any { it.key == found.key }) return
        _state.value = _state.value.copy(
            found = (without + found).sortedWith(
                compareByDescending<Found> { it.byName }.thenBy { it.host }.thenBy { it.port },
            ),
        )
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            true
        }
    }.getOrDefault(false)

    /** This device's own IPv4 addresses and their prefix lengths. */
    private fun localAddresses(): List<Pair<String, Short>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.interfaceAddresses.mapNotNull { ia ->
                    val address = ia.address
                    if (address is Inet4Address) {
                        address.hostAddress?.let { it to ia.networkPrefixLength }
                    } else {
                        null
                    }
                }
            }
    }.getOrDefault(emptyList())

    companion object {

        /**
         * The ports worth asking about, and what each one means.
         *
         * Four, and each corresponds to a provider Filet has. 445 is SMB, which is a Windows
         * share or a NAS; 22 is SSH and therefore SFTP; 21 is FTP; 5005 is the port Filet's own
         * WebDAV host defaults to, which also catches a second phone whose beacon was missed.
         *
         * NOT 80 or 443. A WebDAV server can be on either, but so can every router admin page,
         * printer, smart plug and television on the network - so sweeping them produces a list
         * of things that are not shares and buries the ones that are. A WebDAV server on an
         * unusual port is added by hand, which is the case the form already handles.
         */
        private val PORTS = listOf(
            445 to NetProtocol.SMB,
            22 to NetProtocol.SFTP,
            21 to NetProtocol.FTP,
            5005 to NetProtocol.WEBDAV,
        )

        /** The mDNS service types for the same four, as the rest of the world advertises them. */
        private val SERVICES = listOf(
            "_smb._tcp." to NetProtocol.SMB,
            "_sftp-ssh._tcp." to NetProtocol.SFTP,
            "_ftp._tcp." to NetProtocol.FTP,
            "_webdav._tcp." to NetProtocol.WEBDAV,
        )

        /** How long to wait for a connect before calling an address quiet. */
        private const val CONNECT_MS = 400

        /**
         * How many connects are in flight at once.
         *
         * Thirty-two. Unbounded exhausts the process's file descriptors and starts failing
         * connects that would have succeeded, which makes the scan report FEWER servers on a
         * healthy network - a failure that looks like the feature not working.
         */
        private const val PARALLEL = 32

        /**
         * The addresses to try, from this device's own.
         *
         * ## The two refusals here are the point
         *
         * A prefix shorter than [MIN_PREFIX] is refused rather than walked. A /16 is 65,000
         * connects: useless, slow, and indistinguishable from a scan somebody should be
         * annoyed about. A /24 is 254, which is a few seconds.
         *
         * A link-local address - 169.254.x.x, which is what a phone has when DHCP has not
         * answered - is skipped, because sweeping it finds nothing and costs the same.
         *
         * Pure, so both refusals can be stated in a test rather than discovered on somebody's
         * corporate Wi-Fi.
         */
        fun sweepFor(locals: List<Pair<String, Short>>): List<String>? {
            for ((address, prefix) in locals) {
                if (prefix < MIN_PREFIX) continue
                if (address.startsWith("169.254.")) continue
                val parts = address.split('.')
                if (parts.size != 4) continue
                val own = parts[3].toIntOrNull() ?: continue
                val base = parts.take(3).joinToString(".")
                // 1..254, skipping this device and the broadcast address. Ordered outward from
                // this device's own number, because a router is usually 1 and the interesting
                // machine is usually near by - so the first results arrive sooner.
                val all = (1..254).filter { it != own }
                return all.sortedBy { kotlin.math.abs(it - own) }.map { "$base.$it" }
            }
            return null
        }

        /** Nothing wider than a /24 is swept. See [sweepFor]. */
        const val MIN_PREFIX: Short = 24
    }
}
