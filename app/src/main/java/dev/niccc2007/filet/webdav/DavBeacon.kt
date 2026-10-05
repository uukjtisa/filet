package dev.niccc2007.filet.webdav

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A phone that is hosting says so, and a phone that is looking hears it.
 *
 * ## Why this exists
 *
 * Adding another phone as a remote meant reading an IP off one screen and typing it into another,
 * along with a port and a base path of `/a/<code>`. Every character of that is already known to
 * both devices; the only reason it was being retyped is that neither was saying it out loud.
 *
 * Nearby already advertises `_filet._tcp.` when sharing is on, and that is a different service
 * answering a different question - it carries the Nearby HTTP port and leads to a peer browsed
 * in place. Hosting is a mountable drive, with its own port, its own credential and its own
 * lifetime, so it gets its own service type rather than being crammed into that one as a flag.
 *
 * ## What travels, and what deliberately does not
 *
 * The TXT record carries the base path and what is being shared. **It does not carry the access
 * code.** Broadcasting the password with the address would make the code decorative - anything on
 * the network could mount the phone - and the whole point of the code is that reaching the share
 * takes something the other side was told. Discovery fills in the address; the person supplies the
 * code, once, and it is then remembered like any other connection.
 */
class DavBeacon(private val context: Context) : DavAnnouncer {

    data class State(
        val advertising: Boolean = false,
        val scanning: Boolean = false,
        val hosts: List<DavHost> = emptyList(),
        val error: String? = null,
    )

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** One registration per share, by share id. Several shares ride one port. */
    private val registrations = ConcurrentHashMap<Long, NsdManager.RegistrationListener>()

    /**
     * What each live share announced, kept so it can be announced again.
     *
     * The registration listener alone is not enough to re-register: the platform needs the whole
     * `NsdServiceInfo` back. Without this, reacting to a network change meant asking the server to
     * re-derive every share's details, and the beacon already had them.
     */
    private data class Ad(
        val id: Long,
        val label: String,
        val port: Int,
        val basePath: String,
        val scope: String,
        val writable: Boolean,
    )

    private val ads = ConcurrentHashMap<Long, Ad>()

    /** For retrying a resolve and for the periodic re-scan. Both are delayed work, not loops. */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null

    /**
     * Announce one share. Called when that share starts.
     *
     * Registered per share rather than once per phone, because the shares are separate things to
     * mount - different roots, different permissions - and a single announcement would make a
     * phone offering three of them look like it offered one.
     */
    // No default on `writable` here: the interface declares it and Kotlin refuses to let an
    // override restate one, because then two declarations would each claim to decide it.
    override fun advertise(
        id: Long,
        label: String,
        port: Int,
        basePath: String,
        scope: String,
        writable: Boolean,
    ) {
        val manager = nsd ?: return
        stopAdvertising(id)
        val info = NsdServiceInfo().apply {
            // The label is in the name because the name is the only field a resolver is
            // guaranteed to hand back before the TXT records arrive.
            serviceName = DavDiscovery.serviceName(selfName(), label)
            serviceType = DavDiscovery.SERVICE_TYPE
            this.port = port
            // Attributes rather than a name convention. A service name is one string that every
            // resolver treats differently; TXT records are what DNS-SD has for this.
            setAttribute(DavDiscovery.ATTR_PATH, basePath)
            setAttribute(DavDiscovery.ATTR_SCOPE, scope)
            setAttribute(DavDiscovery.ATTR_WRITE, if (writable) "1" else "0")
            setAttribute(DavDiscovery.ATTR_ID, deviceId(context))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = refreshAdvertising()
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                registrations.remove(id)
                refreshAdvertising()
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) = refreshAdvertising()
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }
        registrations[id] = listener
        ads[id] = Ad(id, label, port, basePath, scope, writable)
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        refreshAdvertising()
    }

    /**
     * Announce every live share again, because the network moved underneath them.
     *
     * A registration is bound to the interface it was made on. When a device changes network the
     * record keeps naming an address that has stopped existing, so anything looking for the share
     * finds nothing - and the only fix available was stopping and starting hosting by hand, which
     * is precisely what this does automatically. [advertise] withdraws first, so re-calling it IS
     * the stop and start.
     */
    fun readvertiseAll() {
        for (ad in ads.values.toList()) {
            advertise(ad.id, ad.label, ad.port, ad.basePath, ad.scope, ad.writable)
        }
    }

    /** Withdraw one share's announcement. */
    override fun stopAdvertising(id: Long) {
        val manager = nsd ?: return
        // The record is dropped by the caller's stop, and restored by [advertise] when this is
        // being called as the first half of a re-announcement.
        ads.remove(id)
        registrations.remove(id)?.let { runCatching { manager.unregisterService(it) } }
        refreshAdvertising()
    }

    /** Withdraw everything. */
    fun stopAdvertising() {
        val manager = nsd ?: return
        ads.clear()
        for (id in registrations.keys.toList()) {
            registrations.remove(id)?.let { runCatching { manager.unregisterService(it) } }
        }
        refreshAdvertising()
    }

    private fun refreshAdvertising() {
        _state.value = _state.value.copy(advertising = registrations.isNotEmpty())
    }

    /**
     * How many callers currently want a scan.
     *
     * There are two now, and that is what this counter is for. The Remotes screen scans while it
     * is open, and the app scans for as long as it is in the foreground so a saved remote can
     * learn the address its device moved to. Without counting, whichever one stopped first
     * stopped the other's scan too - and since the screen stops its scan on dispose, leaving
     * that screen killed the app-wide one. Learning then only happened while somebody was
     * looking at the very screen that made it unnecessary.
     */
    private val scanners = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Listen for other phones hosting.
     *
     * Balanced with [stopScan]: every caller that starts one stops it, and the scan runs while
     * at least one of them wants it.
     */
    fun startScan() {
        if (scanners.incrementAndGet() != 1) return
        beginScan()
    }

    /** Give up this caller's interest in scanning, and stop when it was the last. */
    fun stopScan() {
        if (scanners.updateAndGet { (it - 1).coerceAtLeast(0) } > 0) return
        endScan()
    }

    /**
     * Start listening again from scratch, because what was heard before is no longer true.
     *
     * Called when this device changes network. A discovery listener carries the view it built on
     * the old interface: hosts that were reachable are not, and hosts that are now reachable were
     * never announced to it. Restarting is the only way to get a fresh round of announcements,
     * because mDNS does not re-announce on request.
     *
     * @param forget true when the addresses already collected are known to be stale - which they
     *   are after THIS device moves, and are not after a periodic heal.
     */
    fun restartScan(forget: Boolean) {
        if (scanners.get() <= 0) return
        endScan()
        if (forget) _state.value = _state.value.copy(hosts = emptyList())
        beginScan()
    }

    /**
     * Rebuild the listener periodically while scanning.
     *
     * Not paranoia: NSD drops announcements in ways nothing reports. A resolve that collides with
     * one already in flight fails, a listener can stop delivering after the peer's own network
     * churn, and neither produces an error anybody can act on. The symptom is a host that is on the
     * network and not in the list, which is indistinguishable from the feature being broken - and
     * was being worked around by hand, by opening a screen until it appeared.
     *
     * Hosts already found are kept: this asks for a fresh round of announcements, it does not
     * declare what is known to be wrong.
     */
    private fun scheduleHeal() {
        handler.removeCallbacks(heal)
        handler.postDelayed(heal, HEAL_EVERY_MS)
    }

    private val heal = Runnable {
        if (scanners.get() > 0) {
            endScan()
            beginScan()
        }
    }

    private fun beginScan() {
        val manager = nsd ?: run {
            _state.value = _state.value.copy(error = "This device has no network service discovery.")
            return
        }
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                _state.value = _state.value.copy(scanning = true, error = null)
            }
            override fun onDiscoveryStopped(type: String) {
                _state.value = _state.value.copy(scanning = false)
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                _state.value = _state.value.copy(scanning = false, error = "Discovery failed ($code).")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // This phone's own advertisement comes straight back to it, and listing yourself
                // as something to mount is the first thing every implementation gets wrong.
                if (info.serviceName.contains(selfName())) return
                resolve(manager, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // Matched on the advertised name, which is what a lost notice carries - the
                // address and port are only known after a resolve that will now never happen.
                val lost = DavDiscovery.displayName(info.serviceName)
                _state.value = _state.value.copy(
                    hosts = _state.value.hosts.filterNot { it.name == lost },
                )
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(DavDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        scheduleHeal()
    }

    private fun endScan() {
        val manager = nsd ?: return
        handler.removeCallbacks(heal)
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discovery = null
        _state.value = _state.value.copy(scanning = false)
    }

    /**
     * Turn a found service into an address, retrying when the platform says "not now".
     *
     * A failed resolve used to be discarded without a word, and the most common failure is not
     * about the service at all: the platform resolves one at a time, so a device announcing several
     * shares - or a re-announcement arriving while another resolve is in flight - loses all but the
     * first with `FAILURE_ALREADY_ACTIVE`. From outside, that is a host sitting on the network and
     * never appearing in the list, which is the symptom that was being worked around by opening a
     * screen and waiting.
     */
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, attempt: Int = 0) {
        @Suppress("DEPRECATION")
        runCatching {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                    if (attempt + 1 >= RESOLVE_TRIES) return
                    // Backed off, and only while somebody is still scanning - a retry firing into
                    // a stopped scan would put a host into a list nothing is showing.
                    handler.postDelayed(
                        { if (scanners.get() > 0) resolve(manager, info, attempt + 1) },
                        RESOLVE_BACKOFF_MS * (attempt + 1),
                    )
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val address = pickAddress(i) ?: return
                    val found = DavHost(
                        name = DavDiscovery.displayName(i.serviceName),
                        host = address,
                        port = i.port,
                        basePath = attr(i, DavDiscovery.ATTR_PATH) ?: "/",
                        scope = attr(i, DavDiscovery.ATTR_SCOPE) ?: "",
                        writable = attr(i, DavDiscovery.ATTR_WRITE) == "1",
                        deviceId = attr(i, DavDiscovery.ATTR_ID).orEmpty(),
                    )
                    // Replace by key rather than append. A resolver re-announces, and a list that
                    // appends shows the same phone four times after a minute on the network.
                    _state.value = _state.value.copy(
                        hosts = (_state.value.hosts.filterNot { it.key == found.key } + found)
                            .sortedBy { it.name.lowercase() },
                    )
                }
            })
        }
    }

    /**
     * Every address a resolved service offered, for [DavDiscovery.pickAddress] to choose from.
     *
     * The choosing rule is shared with the PC and lives in that file; this half is the Android
     * plumbing around it. `hostAddresses` is the full list and arrived in API 34, and the
     * deprecated single `host` is what everything below that has - so both are collected and the
     * rule sees whatever this device could offer.
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

    private fun attr(info: NsdServiceInfo, key: String): String? =
        runCatching { info.attributes[key]?.toString(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun selfName(): String =
        runCatching { dev.niccc2007.filet.nearby.PairedPeers.selfName(context) }.getOrDefault("phone")

    private companion object {
        /**
         * How often the listener is rebuilt while scanning.
         *
         * Long enough that it is not a poll - one re-registration a minute costs nothing - and
         * short enough that a host which was missed appears without anybody waiting on it.
         */
        const val HEAL_EVERY_MS = 45_000L

        /**
         * How many times a resolve is retried, and how long apart.
         *
         * `FAILURE_ALREADY_ACTIVE` is the common one and it is not an error about the service: the
         * platform allows one resolve at a time, so a phone announcing three shares loses two of
         * them. They were being dropped silently, which is a host found and never resolved - so it
         * never reached the list and never taught a saved remote anything.
         */
        const val RESOLVE_TRIES = 4
        const val RESOLVE_BACKOFF_MS = 700L

        // The service type, the TXT keys and the name convention are all in [DavDiscovery],
        // because a PC announces the same service through a different library and the two
        // cannot be allowed to spell it differently. See that file for why.

        private const val ID_KEY = "beacon.deviceId"

        /** This install's id, minted on first use and kept. */
        fun deviceId(context: Context): String {
            val sp = context.applicationContext
                .getSharedPreferences("filet-beacon", Context.MODE_PRIVATE)
            sp.getString(ID_KEY, null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString()
            sp.edit().putString(ID_KEY, fresh).apply()
            return fresh
        }
    }
}
