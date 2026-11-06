package dev.niccc2007.filet.remotes

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.webdav.DavDiscovery
import dev.niccc2007.filet.webdav.DavHost
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.flow.update

/**
 * Finding things on this network that Filet can mount - including other Filets.
 *
 * ## The three directions, which is the whole requirement
 *
 * **Others to Filet.** A NAS, a Mac or a Linux box announces `_smb._tcp`, `_sftp-ssh._tcp` or
 * `_webdav._tcp`, and asking costs one multicast packet. A Windows PC announces none of them -
 * it uses WS-Discovery and NetBIOS - so mDNS alone misses the single most common case, which is
 * what the port sweep is for.
 *
 * **Filet to Filet.** Another phone hosting a drive announces [DavDiscovery.SERVICE_TYPE], and
 * `DavBeacon` is the one thing in the process that listens for it. Its resolved hosts are folded
 * into this list by [combine], so they share one section and one row with everything else.
 *
 * This file must NOT open its own listener for that type. It did, for one round, and it broke
 * Filet-to-Filet discovery completely - two resolvers competing for the single resolve slot the
 * platform allows per process. [SERVICES] has the whole account.
 *
 * **Filet to others.** Not this file's half. The beacon advertises `_webdav._tcp` alongside its
 * own type, so a client that has never heard of Filet can still find the phone.
 *
 * ## What it will not do
 *
 * It does not try to log in. A service answering on 445 proves something is there; whether these
 * credentials work is what the Test button is for, and a scan that tried guest credentials
 * against every host on a network would be doing something nobody asked for.
 *
 * It does not leave the subnet. The sweep is built from this device's own interface address and
 * its prefix length, and it refuses anything wider than a /24 - see [sweepFor]. Walking a /16
 * would be 65,000 connects, which is both useless and indistinguishable from an attack.
 *
 * ## What is kept, and for how long
 *
 * This object lives as long as the process, which is the bug the forgetting rules below exist
 * for. A found list that only ever grows shows a host from another network hours later, with a
 * Test button that can never succeed - and it is indistinguishable from discovery working,
 * because something is in the list. So: a withdrawn announcement is removed, a sweep onto a
 * different subnet drops what the last subnet found, and a network move drops the lot. See
 * [merge], [prune] and [onNetworkMoved].
 */
class ServerScan(private val context: Context, private val scope: CoroutineScope) {

    /**
     * @param host the address to connect to.
     * @param name what to call it, when something said.
     * @param protocol what Filet would mount it as.
     * @param port the port it answered on.
     * @param byName true when it announced itself, false when it was swept up.
     * @param path the base path an announcement carried, for a share that is not at the root.
     * @param deviceId the announcing install's own id, where it published one.
     */
    data class Found(
        val host: String,
        val name: String,
        val protocol: NetProtocol,
        val port: Int,
        val byName: Boolean,
        val path: String? = null,
        val deviceId: String = "",
    ) {
        /**
         * One mountable thing.
         *
         * The path is part of it because a phone hosting three shares on one port is offering
         * three different drives - different roots and different permissions - and a key that
         * stopped at the port would collapse them into one row.
         */
        val key: String get() = "$host/$protocol/$port/" + path.orEmpty()

        /**
         * The same thing as a sweep could ever know it: an address, a protocol and a port.
         *
         * What [merge] matches on when deciding whether an announcement supersedes a swept-up
         * row, because a sweep cannot discover a path and so can never produce a matching [key].
         */
        val socket: String get() = "$host/$protocol/$port"
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

    /** The subnet the rows in [state] were collected on, for noticing that it has changed. */
    private var sweptSubnet: String? = null

    private val nsd: NsdManager?
        get() = runCatching {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()

    /** Ask the network to introduce itself. Cheap, and it finds the polite half. */
    fun listen() {
        val manager = nsd ?: return
        if (listeners.isNotEmpty()) return
        // No permission, no listener: without it, Android 17 answers each one with its device
        // picker, and this starts one per server type. See LocalNetwork.
        if (!dev.niccc2007.filet.nearby.LocalNetwork.allowed(context)) return
        for ((type, protocol) in SERVICES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) = Unit
                override fun onDiscoveryStopped(t: String) = Unit
                override fun onStartDiscoveryFailed(t: String, code: Int) = Unit
                override fun onStopDiscoveryFailed(t: String, code: Int) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    runCatching {
                        manager.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, code: Int) = Unit
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    onResolved(resolved, protocol)
                                }
                            },
                        )
                    }
                }

                /**
                 * An announcement withdrawn is a row removed.
                 *
                 * This was `= Unit`, and that is half of why a host nobody can reach sat in the
                 * list for the life of the process. Matched on the advertised NAME, because that
                 * is all a lost notice carries - the address and port are only known after a
                 * resolve that will now never happen.
                 */
                override fun onServiceLost(info: NsdServiceInfo) {
                    val gone = info.serviceName ?: return
                    _state.update { s ->
                        s.copy(found = s.found.filterNot { it.byName && it.name == gone })
                    }
                }
            }
            runCatching {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(type to listener)
            }
        }
        // Without it the answers to these queries can be filtered out before the responder
        // sees them. See [dev.niccc2007.filet.nearby.MulticastHold].
        if (listeners.isNotEmpty()) dev.niccc2007.filet.nearby.MulticastHold.hold(context, "serverscan", true)
    }

    private fun onResolved(resolved: NsdServiceInfo, protocol: NetProtocol) {
        val host = pickAddress(resolved) ?: return
        // Our own `_webdav._tcp` announcement comes straight back to us while hosting, and
        // offering yourself as something to mount is the first thing every implementation of
        // this gets wrong. By ADDRESS, which is what is actually unique per device on a
        // network - the announced name is built from `Build.MODEL` and is not.
        if (isOwn(host, localAddresses().map { it.first }.toSet())) return
        add(
            Found(
                host = host,
                name = resolved.serviceName.orEmpty().ifBlank { host },
                protocol = protocol,
                port = resolved.port.takeIf { it > 0 } ?: protocol.defaultPort,
                byName = true,
                // A third-party announcement says nothing about where a share starts, so the
                // root is the only thing that can be assumed and the form is where it gets
                // corrected. Filet hosts arrive through [combine] and DO carry a path.
                path = null,
            ),
        )
    }

    /**
     * Stop the sweep, and keep listening.
     *
     * The distinction this file was missing, and the reason the Remotes screen stopped finding
     * Filets at all: "Stop looking" was wired to [stop], which unregisters every mDNS listener
     * as well - and nothing re-registers them while the screen stays open. One press ended
     * announcement discovery for as long as the screen lived, which looks exactly like the
     * network having gone quiet.
     */
    fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
        _state.update { it.copy(sweeping = false, progress = 0f) }
    }

    /** Stop everything. For leaving the screen, not for a button. */
    fun stop() {
        val manager = nsd
        for ((_, listener) in listeners) {
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        listeners.clear()
        dev.niccc2007.filet.nearby.MulticastHold.hold(context, "serverscan", false)
        stopSweep()
    }

    /**
     * Start over, because this device moved and nothing in the list is about this network.
     *
     * Every row was an address on the old subnet: the swept ones cannot answer, and the
     * announced ones were resolved through an interface that has gone. A listener carries the
     * view it built there too, and mDNS does not re-announce on request - so the listeners are
     * rebuilt rather than reused. The beacon already did this; the server scan was left out of
     * the same collector, which is why its rows outlived every network they belonged to.
     */
    fun onNetworkMoved() {
        val wasListening = listeners.isNotEmpty()
        stop()
        sweptSubnet = null
        _state.value = State()
        if (wasListening) listen()
    }

    /**
     * Walk the subnet looking for the things Filet can mount.
     *
     * Deliberately a button rather than something that happens on arrival: it is 254 connects,
     * it takes a few seconds, and a screen that does it unasked every time it opens wakes the
     * Wi-Fi radio for nothing most of the time.
     */
    fun sweep() {
        if (sweepJob?.isActive == true) return
        // Without the network permission every one of the connects times out: 254 waits for
        // nothing. The screen asks for it first; this is the guard if anything else calls.
        if (!dev.niccc2007.filet.nearby.LocalNetwork.allowed(context)) return
        val locals = localAddresses()
        val plan = sweepFor(locals)
        if (plan == null) {
            _state.update {
                it.copy(
                    note = "Filet could not work out this network's shape. A hotspot or a VPN " +
                        "often has no ordinary subnet to walk.",
                )
            }
            return
        }
        // Rows from a subnet this device is no longer on are dropped before new ones arrive, so
        // the list describes one network rather than every network the process has seen.
        val subnet = subnetOf(plan.first())
        val mine = locals.map { it.first }.toSet()
        _state.update {
            it.copy(
                found = prune(it.found, subnet, sweptSubnet),
                sweeping = true,
                progress = 0f,
                note = null,
            )
        }
        sweptSubnet = subnet
        sweepJob = scope.launch {
            // Bounded concurrency. Unbounded, this is 254 x 4 sockets at once, which on a phone
            // exhausts the file-descriptor limit and starts failing connects that would
            // otherwise have succeeded - so the scan would report FEWER servers the better the
            // network was.
            val gate = Semaphore(PARALLEL)
            // Atomic because PARALLEL coroutines report through it. `done++` on a plain Int lost
            // increments, which is a progress bar that never reaches the end.
            val done = AtomicInteger(0)
            val total = plan.size * PORTS.size
            // What this run actually reached, so the end of the run can retire what it did not.
            val confirmed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            withContext(Dispatchers.IO) {
                for (host in plan) {
                    for ((port, protocol) in PORTS) {
                        launch {
                            gate.withPermit {
                                if (!isOwn(host, mine) && reachable(host, port)) {
                                    val hit = Found(
                                        host = host,
                                        // Asked for by name, because a row reading
                                        // `smb://192.168.5.13:445` is unidentifiable - it reads
                                        // as something unknown sitting on the network when it is
                                        // usually the person's own PC. Windows answers a reverse
                                        // lookup over NetBIOS, so this is the difference between
                                        // a scary address and "DESKTOP-whatever".
                                        name = nameOf(host),
                                        protocol = protocol,
                                        port = port,
                                        byName = false,
                                    )
                                    confirmed.add(hit.socket)
                                    add(hit)
                                }
                            }
                            val at = done.incrementAndGet()
                            _state.update {
                                it.copy(progress = (at.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            // Only reached when the sweep RAN TO THE END - a cancelled one throws out of the
            // block above, which is the point: a sweep stopped half way has not tested the
            // addresses it never got to, and retiring their rows would delete working servers.
            _state.update {
                it.copy(found = settle(it.found, confirmed), sweeping = false, progress = 1f)
            }
        }
    }

    private fun add(found: Found) {
        // `update` rather than read-copy-write: PARALLEL sweep coroutines and the resolver
        // callbacks all land here, and the plain version silently dropped whichever row lost the
        // race - a server found and not listed, which reads as the scan having missed it.
        _state.update { it.copy(found = merge(it.found, found)) }
    }

    /**
     * What a host calls itself, or its address when it will not say.
     *
     * Only ever called for an address that already ANSWERED. A reverse lookup has no timeout
     * argument and blocks until the resolver gives up, so doing this for all 254 addresses
     * would add that wait to every quiet one - which is most of them. For the handful that
     * answered it is worth the wait, because an unnamed row is the one complaint this whole
     * sweep generates: the address of the person's own desktop, listed as if it were a stranger.
     */
    private fun nameOf(host: String): String = runCatching {
        val found = java.net.InetAddress.getByName(host).hostName
        // `getHostName` hands the address straight back when nothing answers, which is not a
        // name - and a "name" that is a dotted quad would defeat the dedup in `merge`.
        if (found.isNullOrBlank() || found == host) host else found
    }.getOrDefault(host)

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            true
        }
    }.getOrDefault(false)

    private fun attr(info: NsdServiceInfo, key: String): String? =
        runCatching { info.attributes[key]?.toString(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Every address a resolved service offered, narrowed by the rule both ends share.
     *
     * `hostAddresses` is the full list and arrived in API 34; the deprecated single `host` is
     * what everything below that has. Both are collected so the rule sees whatever this device
     * could offer - see [DavDiscovery.pickAddress] for why the choice is not the resolver's.
     */
    private fun pickAddress(info: NsdServiceInfo): String? {
        val candidates = mutableListOf<java.net.InetAddress>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { candidates.addAll(info.hostAddresses) }
        }
        @Suppress("DEPRECATION")
        runCatching { info.host?.let { candidates.add(it) } }
        return DavDiscovery.pickAddress(candidates)
    }

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
         * Each corresponds to a provider Filet has. 445 is SMB, which is a Windows share or a
         * NAS; 22 is SSH and therefore SFTP; 21 is FTP; 5005 is the port Filet's own WebDAV host
         * defaults to, which also catches a second phone whose announcement was missed.
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

        /**
         * The service types asked about, as the rest of the world advertises them.
         *
         * **Filet's own type is deliberately NOT here, and must not be added.**
         *
         * It was, for one round, to get other Filets into this list - and it broke Filet-to-Filet
         * discovery outright. `NsdManager` serves ONE resolve at a time per process: that is why
         * `DavBeacon.resolve` carries a four-try backoff for `FAILURE_ALREADY_ACTIVE`. A second
         * listener on the same type in the same process meant two resolvers competing for that
         * one slot, and this one had no retry at all - so it took the slot, failed silently, and
         * left the beacon's resolve backing off until it gave up. The host then appeared in
         * NEITHER list.
         *
         * The symptom was perfectly asymmetric and that is what identified it: a build with one
         * listener could see a build with two, and the build with two could see nothing.
         *
         * One discovery per service type per process. Other Filets reach this screen through
         * [combine], which folds the beacon's ALREADY-RESOLVED hosts into the same list.
         */
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
         * A Filet host the beacon has already resolved, as a row in this list.
         *
         * The Filet-to-Filet half, and it goes through the beacon rather than through a second
         * discovery of the same type - see [SERVICES] for the outage that taught us why. The
         * beacon has already done the resolve and has the TXT records, so this is a mapping and
         * not a lookup.
         */
        fun fromHost(host: DavHost): Found = Found(
            host = host.host,
            name = host.name.ifBlank { host.host },
            protocol = NetProtocol.WEBDAV,
            port = host.port,
            byName = true,
            path = host.basePath.trim('/').takeIf { it.isNotEmpty() },
            deviceId = host.deviceId,
        )

        /**
         * One list: what the scan turned up, plus the Filet hosts the beacon heard.
         *
         * Folded through [merge] rather than concatenated, so a Filet phone the sweep also found
         * on port 5005 collapses into the named row instead of appearing twice - once as itself
         * and once as a bare address.
         *
         * Pure, and it is the reason the screen no longer has two discovery sections. Two lists
         * that could each contain the same phone is not a layout problem, it is the screen
         * asking somebody to work out whether two rows are one device.
         */
        fun combine(servers: List<Found>, hosts: List<DavHost>): List<Found> {
            var out = servers
            for (h in hosts) out = merge(out, fromHost(h))
            return out
        }

        /**
         * Whether [host] is this device.
         *
         * Pure, and it replaces a service-NAME test that could not work: the announced name is
         * built from `Build.MODEL`, so two phones of the same model each decided the other's
         * announcement was its own and discarded it. An address is the thing that is actually
         * unique per device on a network.
         */
        fun isOwn(host: String, locals: Set<String>): Boolean = host in locals

        /** The /24 an address sits on, as a string to compare. */
        fun subnetOf(address: String): String? {
            val parts = address.split('.')
            if (parts.size != 4) return null
            if (parts.any { it.toIntOrNull() == null }) return null
            return parts.take(3).joinToString(".")
        }

        /**
         * What survives a sweep that is starting on [subnet].
         *
         * Swept rows are addresses and nothing else, so one from another subnet is a row whose
         * Test button can never succeed. Announced rows are kept whatever subnet they name: an
         * announcement is live by definition - it is withdrawn through `onServiceLost` - and a
         * phone reachable across two interfaces legitimately announces an address that is not on
         * the one being swept.
         *
         * Pure, because "drop the stale rows" is one line away from "drop the list", and that is
         * the failure nobody notices until discovery looks broken.
         */
        fun prune(found: List<Found>, subnet: String?, previous: String?): List<Found> {
            if (subnet == null || subnet == previous) return found
            return found.filter { it.byName || subnetOf(it.host) == subnet }
        }

        /**
         * What survives a sweep that has just finished, given the sockets it reached.
         *
         * A sweep re-establishes every swept row by definition, so one it did not reach this
         * time is a server that has gone - and leaving it there is the other half of the bug
         * where a host nobody can connect to sits in the list for the life of the process. The
         * subnet rule in [prune] cannot catch this one: the dead host is on the subnet being
         * swept, which is exactly why it looked like a legitimate row.
         *
         * Announced rows are untouched. A sweep tests four ports and knows nothing about what
         * announced itself on a fifth, so treating silence here as absence would delete the
         * named hosts - the ones somebody actually wanted - on every sweep.
         */
        fun settle(found: List<Found>, confirmed: Set<String>): List<Found> =
            found.filter { it.byName || it.socket in confirmed }

        /**
         * How an announcement and a swept-up row for the same thing become one row.
         *
         * A host that ANNOUNCED itself wins: it carries a name, and a name is the whole reason
         * somebody wanted discovery. Matched on [Found.socket] rather than [Found.key] because a
         * sweep cannot discover a path, so the keys never agree and both rows would show - one
         * phone listed twice, once as itself and once as a bare IP address.
         *
         * Pure, so the replacement rule can be stated in a test. The version this replaces
         * returned early on a condition that also held for a row it had not yet stored, which
         * dropped announcements arriving after a sweep had already found the same socket.
         */
        fun merge(existing: List<Found>, found: Found): List<Found> {
            if (existing.any { it.key == found.key && it.byName == found.byName }) return existing
            // A swept row adds nothing to a socket something has already announced by name.
            if (!found.byName && existing.any { it.byName && it.socket == found.socket }) {
                return existing
            }
            val kept = existing.filterNot {
                it.key == found.key || (found.byName && !it.byName && it.socket == found.socket)
            }
            return (kept + found).sortedWith(
                compareByDescending<Found> { it.byName }.thenBy { it.host }.thenBy { it.port },
            )
        }

        /**
         * The addresses to try, from this device's own.
         *
         * ## The two refusals here are the point
         *
         * A prefix shorter than [MIN_PREFIX] is refused rather than walked. A /16 is 65,000
         * connects: useless, slow, and indistinguishable from a scan somebody should be annoyed
         * about. A /24 is 254, which is a few seconds.
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
