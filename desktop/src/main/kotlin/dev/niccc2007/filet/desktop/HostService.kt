/**
 * # The hosting half of Filet Desktop - wiring, not an implementation
 *
 * This half offers folders from this PC to the network, so that Filet on a phone sees them
 * appear by themselves and mounts them like any other phone's share. It owns four things and
 * nothing else: the [Config] (shares, the [Account], the port, the autostart flag), the
 * drive-root guard ([ShareGuard]), the mDNS announcement and listener ([MdnsBeacon], [PeerWatch]),
 * and the glue in [HostService] that hands configured folders to the server.
 *
 * **`:core-dav` supplies the protocol, all of it.** `WebDavServer` is the HTTP framing, the
 * thread model, every DAV verb, the XML bodies, ranges, locks, quota and the path decode; the
 * shared `DavDiscovery` is the service type and every TXT key. `:core-vfs-jvm` supplies the
 * router and the local-disk provider the server reads through.
 *
 * **The one-copy rule.** The phone runs the same `WebDavServer` from the same module. Both ends
 * emit the same XML and decode paths the same way because there is ONE copy, and every corner
 * in it - locks, ranged reads, quota properties, percent-decoded paths with a `+` in them, the
 * PUT body the connection loop must never read - was paid for once already. Nothing in this
 * package answers a DAV verb, writes a multistatus body or decodes a request path. A change the
 * server needs belongs in `:core-dav`, where the phone gets it too; a second implementation here
 * is an offer to pay for those corners twice.
 */
package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import dev.niccc2007.filet.webdav.DavShare
import dev.niccc2007.filet.webdav.DavState
import dev.niccc2007.filet.webdav.WebDavServer
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One running share as the window and the headless printout show it.
 *
 * [url] is built on the chosen LAN address. When the PC has several and none could be chosen,
 * [confident] is false, [url] is merely the first, and [urls] holds one per address with its
 * adapter's name - show those instead of pretending to know.
 */
data class Endpoint(
    val name: String,
    val path: String,
    val url: String,
    val writable: Boolean,
    val urls: List<AddressUrl> = emptyList(),
    val confident: Boolean = true,
)

/** One way to reach a share: the adapter, its address and the full one-string URL. */
data class AddressUrl(val adapter: String, val ip: String, val url: String)

/**
 * Configured folders, served by `:core-dav`'s `WebDavServer`, announced over mDNS.
 *
 * Every [Share] becomes its own server-side share with its own code ([Account.codeFor]) and its
 * own announcement, exactly as the phone hosts several. Decision: per-share and not one share over
 * a composite tree of every folder. The composite reads more like one account, but the server's
 * write gate is per share - with one share, a PUT into a read-only folder would pass the gate,
 * fail in the provider with a 500, and leave its body on the connection to be parsed as the next
 * request. Per-share keeps the refusal where `WebDavServer.refuse` drains the body correctly.
 *
 * Codes are pinned and every share never idles, so the endpoint a PC or phone stored survives a
 * restart of the tool.
 */
class HostService(
    private val store: ConfigStore = ConfigStore(),
    mdns: Mdns? = Mdns(),
    private val selfName: String = machineName(),
) {
    @Volatile private var config: Config = store.load()

    private val server = WebDavServer(
        vfs = Vfs(listOf(LocalProvider(rootDirs = emptyList()))),
        // Never consulted: every share is given a customRoot, which the server prefers.
        rootFor = { null },
        onEvent = { publish(it) },
    )

    val beacon: MdnsBeacon? = mdns?.let { MdnsBeacon(it, selfName, config.deviceId) }

    /** server-side id -> the share it was built from, as of the last [start]. */
    @Volatile private var byId: Map<Long, Share> = emptyMap()
    @Volatile private var candidates: List<HostAddress> = emptyList()
    @Volatile private var peerHosts: List<String> = emptyList()
    @Volatile private var last: DavState? = null

    private val _shares = MutableStateFlow(config.shares)
    private val _running = MutableStateFlow(false)
    private val _address = MutableStateFlow<String?>(null)
    private val _endpoints = MutableStateFlow<List<Endpoint>>(emptyList())
    private val _port = MutableStateFlow(0)
    private val _missing = MutableStateFlow<List<Share>>(emptyList())
    private val _addresses = MutableStateFlow<List<HostAddress>>(emptyList())
    private val _chosen = MutableStateFlow<HostAddress?>(null)

    /** Every site-local address this PC holds, refreshed on each start and each peer change. */
    val addresses: StateFlow<List<HostAddress>> = _addresses.asStateFlow()

    /** The address the URLs are built on, or null when it could not be told apart from the rest. */
    val chosenAddress: StateFlow<HostAddress?> = _chosen.asStateFlow()

    /** Configured shares whose folder was absent at the last [start], so they are not served. */
    val missing: StateFlow<List<Share>> = _missing.asStateFlow()

    val shares: StateFlow<List<Share>> = _shares.asStateFlow()
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * `http://<ip>:<port>/` while anything is listening and the address is known, else null.
     * Null while running means several addresses and no way to choose: read [addresses].
     */
    val address: StateFlow<String?> = _address.asStateFlow()

    /** Each running share's full one-string address. */
    val endpoints: StateFlow<List<Endpoint>> = _endpoints.asStateFlow()

    /** The port actually bound, or 0. Differs from [preferredPort] when that one was taken. */
    val port: StateFlow<Int> = _port.asStateFlow()

    val preferredPort: Int get() = config.port
    val password: String get() = config.account.password
    val deviceId: String get() = config.deviceId
    val configFile get() = store.file

    var autostart: Boolean
        get() = config.autostart
        set(value) = update(config.copy(autostart = value))

    /**
     * Tell the host which Filet peers are on the network, so it can pick the address that shares
     * their subnet. Wire it to `PeerWatch.peers`. Cheap to call often: it only re-reads the
     * interfaces when the set of peer hosts actually changes.
     */
    fun notePeers(peers: List<Peer>) {
        val hosts = peers.map { it.host }.distinct().sorted()
        if (hosts == peerHosts) return
        peerHosts = hosts
        candidates = LanAddress.candidates()
        republish()
    }

    /** Pick the address by hand, or null to go back to choosing automatically. Persisted. */
    fun chooseAddress(ip: String?) {
        update(config.copy(host = ip))
        candidates = LanAddress.candidates()
        republish()
    }

    /**
     * Set [path]'s access code, or mint a new one when [code] is null. A refusal reason, or null.
     *
     * Saved immediately, because the point of a code is that it survives a restart. Upper case is
     * folded to lower rather than refused, since the alphabet is lower case and the comparison
     * is exact.
     *
     * A share that is running is restarted ALONE, because `WebDavServer` captures the code when a
     * share starts and never re-reads it (`DavShares.needsRestart`). Other shares and their
     * clients are untouched. A drive mapped to the old address stops resolving and must be mapped
     * again: that is what changing a password means, not a side effect.
     */
    @Synchronized
    fun setCode(path: String, code: String?): String? {
        val target = config.shares.firstOrNull { Shares.samePath(it.path, path) }
            ?: return "That folder is not shared any more."
        val account = config.account
        val wanted = code?.trim()?.lowercase()
            ?: generateSequence { Account.generate() }.first { account.refuseCode(it, target, config.shares) == null }
        account.refuseCode(wanted, target, config.shares)?.let { return it }
        replaceShare(target.copy(code = wanted), restart = true)
        return null
    }

    /**
     * Rename a share - its label only.
     *
     * The tag is NOT touched, and must not be: it is part of the endpoint, so a rename that moved
     * it would silently break every drive mapped to the share. A running share takes the new name
     * live, and its announcement is re-registered under it.
     */
    @Synchronized
    fun rename(path: String, name: String) {
        val target = config.shares.firstOrNull { Shares.samePath(it.path, path) } ?: return
        val clean = name.trim()
        if (clean.isEmpty() || clean == target.name) return
        val label = Shares.freeName(config.shares.filterNot { it.path == target.path }, clean)
        replaceShare(target.copy(name = label), restart = false)
    }

    /** Swap [updated] in for the share at its path, persist it, and bring a live share up to date. */
    private fun replaceShare(updated: Share, restart: Boolean) {
        update(config.copy(shares = config.shares.map { if (Shares.samePath(it.path, updated.path)) updated else it }))
        val id = byId.entries.firstOrNull { Shares.samePath(it.value.path, updated.path) }?.key ?: return
        byId = byId + (id to updated)
        val dav = toDav(id, updated)
        server.shares = server.shares.map { if (it.id == id) dav else it }
        if (!server.isRunning()) return
        if (restart) {
            server.stop(id)
            server.start(id)
        } else {
            server.applyLive(dav)
        }
    }

    /** Change the preferred port; takes effect on the next [start]. */
    fun setPort(p: Int): String? {
        if (p !in 1024..65535) return "Pick a port between 1024 and 65535."
        update(config.copy(port = p))
        return null
    }

    /** Add a folder, or say why it cannot be. Restarts a running server so it is served at once. */
    fun addShare(path: String, writable: Boolean, name: String? = null): String? {
        Shares.checkOnDisk(path)?.let { return it }
        return when (val r = Shares.add(config.shares, path, name, writable)) {
            is Shares.Added.Refused -> r.reason
            is Shares.Added.Ok -> { update(config.copy(shares = r.shares)); restartIfRunning(); null }
        }
    }

    fun removeShare(path: String) {
        update(config.copy(shares = config.shares.filterNot { Shares.samePath(it.path, path) }))
        restartIfRunning()
    }

    /**
     * Turn writing on or off, live.
     *
     * Applied to the running share directly and not by a restart: revoking write access has to
     * be instant, and a restart would drop every client mid-copy to get there.
     */
    fun setWritable(path: String, writable: Boolean) {
        update(config.copy(shares = config.shares.map { if (Shares.samePath(it.path, path)) it.copy(writable = writable) else it }))
        val id = byId.entries.firstOrNull { Shares.samePath(it.value.path, path) }?.key ?: return
        server.shares = server.shares.map { if (it.id == id) it.copy(writable = writable) else it }
        server.setWritable(id, writable)
    }

    /** Start serving every configured folder. Null when serving, else why not. */
    @Synchronized
    fun start(): String? {
        if (server.isRunning()) return null
        val present = config.shares.filter { Shares.present(it.path) }
        if (config.shares.isEmpty()) return "Nothing is shared yet. Add a folder first."
        if (present.isEmpty()) return "None of the shared folders can be found."
        candidates = LanAddress.candidates()
        val ids = present.mapIndexed { i, s -> (i + 1L) to s }.toMap()
        byId = ids
        server.preferredPort = config.port
        server.shares = ids.map { (id, s) -> toDav(id, s) }
        for (id in ids.keys) server.start(id)
        if (!server.isRunning()) return "Could not open a port to listen on."
        // Serving the folders that are there beats refusing them all because a USB drive is out.
        // The rest are reported, not silently dropped.
        _missing.value = config.shares - present.toSet()
        return null
    }

    @Synchronized
    fun stop() {
        server.stop()
    }

    /** Stop, and wait for the goodbye packets so phones drop the rows now rather than at TTL. */
    fun close() {
        stop()
        beacon?.withdrawAll()
    }

    private fun restartIfRunning() {
        if (!server.isRunning()) return
        stop()
        start()
    }

    private fun update(c: Config) {
        config = c
        store.save(c)
        _shares.value = c.shares
    }

    private fun toDav(id: Long, s: Share) = DavShare(
        id = id,
        label = s.name,
        customRoot = folderVPath(s.path).toString(),
        code = config.account.codeFor(s),
        writable = s.writable,
        // The flat indexed views need an index this tool does not have, and with views on a
        // real subfolder sharing the views' name would be routed to them instead of to the disk.
        views = false,
        // A PC hosts for as long as the tool runs; an idle stop would move nothing but would
        // make a mapped drive fail until somebody restarted hosting by hand.
        idleMinutes = 0,
        autoStart = true,
    )

    private fun publish(st: DavState) {
        last = st
        val cands = candidates
        val chosen = LanAddress.choose(cands, peerHosts, config.host)
        _addresses.value = cands
        _chosen.value = chosen
        _running.value = st.running
        _port.value = st.port
        _address.value = if (st.running && chosen != null) Address.base(chosen.ip, st.port) else null
        val fallback = chosen?.ip ?: cands.firstOrNull()?.ip ?: "127.0.0.1"
        _endpoints.value = st.liveShares.mapNotNull { live ->
            val s = byId[live.id] ?: return@mapNotNull null
            Endpoint(
                name = s.name,
                path = s.path,
                url = Address.of(fallback, st.port, live.code),
                writable = live.writable,
                urls = cands.map { AddressUrl(it.adapter, it.ip, Address.of(it.ip, st.port, live.code)) },
                confident = chosen != null || cands.size <= 1,
            )
        }
    }

    private fun republish() {
        val st = last
        if (st != null) {
            publish(st)
        } else {
            _addresses.value = candidates
            _chosen.value = LanAddress.choose(candidates, peerHosts, config.host)
        }
    }

    companion object {
        /**
         * A folder as the VPath `LocalProvider` itself produces for it: separators folded and a
         * leading slash, so `D:\Pictures` is `local:///D:/Pictures`. The JDK resolves `/D:/x` to
         * `D:\x` on Windows, which is what `LocalProvider.toFile` relies on.
         */
        fun folderVPath(path: String): VPath = VPath.of(LocalProvider.SCHEME, path.replace('\\', '/'))

        /**
         * The name phones show. The environment first: `getLocalHost` can block on a DNS lookup
         * for seconds on a machine whose hostname does not resolve.
         */
        fun machineName(): String =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "PC"
    }
}
