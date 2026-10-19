package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.webdav.DavAnnouncer
import dev.niccc2007.filet.webdav.DavDiscovery
import dev.niccc2007.filet.webdav.DavHost
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Another Filet host seen on the network, phone or PC. */
data class Peer(
    val name: String,
    val host: String,
    val port: Int,
    /**
     * Always false today. No TXT key carries it and neither end serves TLS; it is here so a
     * future key has a field to land in without changing the contract.
     */
    val secure: Boolean,
    /** `/a/` - the part of `/a/<code>` that is not a secret. */
    val base: String,
    /** What is shared, from TXT `scope`: "Whole phone", "Filet/Shared" or a folder name. */
    val scope: String = "",
    /** TXT `w`. Advisory: the far end re-checks every request. */
    val writable: Boolean = false,
    /** TXT `id`, stable across networks; empty from a host too old to send it. */
    val deviceId: String = "",
) {
    /** Same identity rule as [DavHost.key]: one device can offer several shares on one port. */
    val key: String get() = "$host:$port/$name"

    companion object {
        fun of(h: DavHost): Peer = Peer(h.name, h.host, h.port, false, h.basePath, h.scope, h.writable, h.deviceId)
    }
}

/**
 * The TXT record, both directions, as pure functions.
 *
 * Every key and the service type come from [DavDiscovery] in `:core-dav` - the file the phone's
 * `DavBeacon` reads them from too. Two spellings of one wire format produce two networks that
 * cannot see each other with no error anywhere, so neither end is allowed its own copy.
 */
object PeerTxt {

    /**
     * JmDNS wants the domain on the type; `NsdManager` does not. Derived from the shared constant
     * rather than written out, so the two can never drift apart.
     */
    const val JMDNS_TYPE = DavDiscovery.SERVICE_TYPE + "local."

    /** What the phone's `DavBeacon.advertise` sets, key for key and value for value. */
    fun encode(basePath: String, scope: String, writable: Boolean, deviceId: String): Map<String, String> =
        linkedMapOf(
            DavDiscovery.ATTR_PATH to basePath,
            DavDiscovery.ATTR_SCOPE to scope,
            DavDiscovery.ATTR_WRITE to if (writable) "1" else "0",
            DavDiscovery.ATTR_ID to deviceId,
        )

    /** A resolved service as the shared [DavHost], with the same defaults the phone applies. */
    fun decode(serviceName: String, host: String, port: Int, attr: (String) -> String?): DavHost {
        fun a(k: String) = attr(k)?.takeIf { it.isNotBlank() }
        return DavHost(
            name = DavDiscovery.displayName(serviceName),
            host = host,
            port = port,
            basePath = a(DavDiscovery.ATTR_PATH) ?: "/",
            scope = a(DavDiscovery.ATTR_SCOPE) ?: "",
            writable = a(DavDiscovery.ATTR_WRITE) == "1",
            deviceId = a(DavDiscovery.ATTR_ID).orEmpty(),
        )
    }

    /**
     * Whether a heard announcement is this PC's own.
     *
     * By device id, not by name: names are clipped to 20 characters and two PCs called
     * `DESKTOP-...` collide, which would hide a real peer. An empty id proves nothing.
     */
    fun isSelf(h: DavHost, selfId: String): Boolean = selfId.isNotEmpty() && h.deviceId == selfId
}

/**
 * The one mDNS responder, shared by the beacon and the watch.
 *
 * `JmmDNS` rather than `JmDNS`: a single `JmDNS` binds one interface, and both picking the right
 * one and noticing when the PC moves network are then this tool's problem - the second is the
 * exact fault the phone's `NetworkWatch` exists for, where an announcement kept naming an address
 * that had gone. `JmmDNS` runs one responder per interface and re-checks the set periodically, so
 * a laptop moving from Ethernet to Wi-Fi re-announces without a restart.
 */
class Mdns {
    private var dns: JmmDNS? = null

    @Synchronized
    fun get(): JmmDNS = dns ?: JmmDNS.Factory.getInstance().also { dns = it }

    @Synchronized
    fun close() {
        if (dns == null) return
        dns = null
        runCatching { JmmDNS.Factory.close() }
    }
}

/**
 * This PC's shares on the network: the desktop's [DavAnnouncer], as `DavBeacon` is the phone's.
 *
 * Registration runs on one background thread. `registerService` blocks through mDNS probing,
 * which is a second or more per share, and `WebDavServer.start` calls [advertise] on its caller's
 * thread - the window would freeze for as long as there are shares. One thread and not a pool
 * keeps a withdraw-then-advertise pair in order.
 */
class MdnsBeacon(
    private val mdns: Mdns,
    private val selfName: String,
    private val deviceId: String,
) : DavAnnouncer {

    private val live = ConcurrentHashMap<Long, ServiceInfo>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "filet-mdns-ads").apply { isDaemon = true } }

    /** The advertised instance names, for the headless printout. */
    fun names(): List<String> = live.values.map { it.name }

    override fun advertise(id: Long, label: String, port: Int, basePath: String, scope: String, writable: Boolean) {
        val info = ServiceInfo.create(
            PeerTxt.JMDNS_TYPE,
            DavDiscovery.serviceName(selfName, label),
            port, 0, 0,
            PeerTxt.encode(basePath, scope, writable, deviceId),
        )
        val old = live.put(id, info)
        worker.execute {
            runCatching { old?.let { mdns.get().unregisterService(it) } }
            runCatching { mdns.get().registerService(info) }
        }
    }

    override fun stopAdvertising(id: Long) {
        val old = live.remove(id) ?: return
        worker.execute { runCatching { mdns.get().unregisterService(old) } }
    }

    /**
     * Withdraw everything and wait for it, so goodbye packets leave before the process does.
     * Without the wait a Ctrl+C exits first, and phones keep a dead row until its TTL runs out.
     */
    fun withdrawAll(timeoutMs: Long = 3_000) {
        val all = live.values.toList()
        live.clear()
        val done = worker.submit { all.forEach { runCatching { mdns.get().unregisterService(it) } } }
        runCatching { done.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }
}

/**
 * Filet hosts on this network, phone or PC, as they come and go.
 *
 * Mirrors the phone's scan: found services are resolved, keyed by [Peer.key] so a re-announcement
 * replaces its row rather than duplicating it, and removed by display name because a removal
 * notice carries the name and nothing resolved. A peer that leaves without a goodbye packet goes
 * when its records expire, which is the protocol's answer and the phone's too.
 */
class PeerWatch(private val mdns: Mdns, private val selfId: String) {

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    @Volatile private var listener: ServiceListener? = null

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : ServiceListener {
            override fun serviceAdded(e: ServiceEvent) {
                // Asynchronous: the answer arrives as serviceResolved. Blocking in a JmDNS
                // callback stalls its event thread for every other service.
                runCatching { mdns.get().requestServiceInfo(e.type, e.name) }
            }

            override fun serviceRemoved(e: ServiceEvent) {
                val gone = DavDiscovery.displayName(e.name)
                _peers.update { list -> list.filterNot { it.name == gone } }
            }

            override fun serviceResolved(e: ServiceEvent) {
                val info = e.info ?: return
                val host = DavDiscovery.pickAddress((info.inetAddresses ?: emptyArray<InetAddress>()).toList()) ?: return
                val h = PeerTxt.decode(info.name, host, info.port) { k -> info.getPropertyString(k) }
                if (PeerTxt.isSelf(h, selfId)) return
                val p = Peer.of(h)
                _peers.update { list -> (list.filterNot { it.key == p.key } + p).sortedBy { it.name.lowercase() } }
            }
        }
        listener = l
        runCatching { mdns.get().addServiceListener(PeerTxt.JMDNS_TYPE, l) }
    }

    /**
     * Ask the network again without forgetting what is already listed.
     *
     * `stop()` then `start()` also re-queries, but it empties the list first, so a PC that is
     * still there vanishes for a moment and comes back - which reads as the network being flaky.
     * Here the listener is re-registered (JmDNS sends a fresh query for the type on registration)
     * and each known peer is re-resolved in place. A peer that has really gone still leaves the
     * normal way, on its goodbye or when its records expire.
     */
    @Synchronized
    fun rescan() {
        val l = listener ?: return start()
        runCatching {
            val dns = mdns.get()
            dns.removeServiceListener(PeerTxt.JMDNS_TYPE, l)
            dns.addServiceListener(PeerTxt.JMDNS_TYPE, l)
            for (p in _peers.value) dns.requestServiceInfo(PeerTxt.JMDNS_TYPE, DavDiscovery.NAME_PREFIX + p.name)
        }
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { mdns.get().removeServiceListener(PeerTxt.JMDNS_TYPE, l) }
        _peers.value = emptyList()
    }
}
