package dev.niccc2007.filet.nearby

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** How a peer was found. Shown on the row, because it is the first diagnostic when a
 *  transfer fails - and because it teaches that Wi-Fi Direct exists. */
enum class FoundBy { MDNS, MANUAL, PAIRED }

data class Peer(
    val uuid: String,
    val name: String,
    val model: String,
    val host: String,
    val port: Int,
    val foundBy: FoundBy,
    val lastSeen: Long,
    val paired: Boolean,
    val reachable: Boolean = true,
) {
    val label: String get() = name.ifEmpty { host }
    val base: String get() = "http://$host:$port"
}

/**
 * Peers this device has paired with.
 *
 * Pairing is remembered by **UUID plus a shared token**, not by address: a phone changes IP
 * every time it rejoins a network, and a pairing keyed on the address would evaporate.
 */
object PairedPeers {

    private const val PREF = "filet-peers"
    private const val KEY = "paired"

    fun all(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.getOrElse { emptyList() }
    }

    fun isTrusted(context: Context, uuid: String): Boolean =
        all(context).any { it.optString("uuid") == uuid }

    fun tokenFor(context: Context, uuid: String): String? =
        all(context).firstOrNull { it.optString("uuid") == uuid }?.optString("token")

    fun remember(context: Context, uuid: String, name: String, token: String) {
        val list = all(context).filterNot { it.optString("uuid") == uuid }
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        arr.put(JSONObject().put("uuid", uuid).put("name", name).put("token", token).put("at", System.currentTimeMillis()))
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun forget(context: Context, uuid: String) {
        val arr = JSONArray()
        all(context).filterNot { it.optString("uuid") == uuid }.forEach { arr.put(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * This instance's identity.
     *
     * Generated per install rather than derived from the signing key: per-install is the more
     * private of the two options in NEARBY.md §7, and the cost - a reinstall loses existing
     * pairings - is a one-time re-pair rather than a privacy property given away forever.
     */
    fun selfUuid(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.getString("self", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        sp.edit().putString("self", id).apply()
        return id
    }

    fun selfName(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getString("selfName", null)
            ?: (android.os.Build.MODEL ?: "Android").also { sp.edit().putString("selfName", it).apply() }
    }

    fun setSelfName(context: Context, name: String) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("selfName", name).apply()

    fun newToken(): String {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /**
     * The four-digit short authentication string shown on both screens.
     *
     * Trust-on-first-use over a self-signed certificate is MITM-able on a hostile network.
     * Deriving a code from both UUIDs and the session token means a machine in the middle
     * would have to make two different pairs of numbers agree, which it cannot.
     */
    fun shortCode(uuidA: String, uuidB: String, token: String): String {
        val ordered = listOf(uuidA, uuidB).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ordered[0] + ordered[1] + token).toByteArray())
        val n = ((digest[0].toInt() and 0xFF) shl 8 or (digest[1].toInt() and 0xFF)) % 10000
        return "%04d".format(n)
    }
}

/**
 * mDNS discovery, on Android's own `NsdManager`.
 *
 * `NsdManager` rather than jmdns: it is in the platform, it holds the multicast lock itself,
 * and a bundled mDNS stack on Android is a well-known source of battery complaints.
 *
 * This finds peers on a shared network. It does **not** work through AP client isolation -
 * which most hotel and many hotspot networks enable - and that failure is diagnosable rather
 * than mysterious: mDNS finds the peer, TCP then refuses. [DiscoveryState.isolationSuspected]
 * carries that signal so the UI can say what is actually wrong.
 */
class NearbyDiscovery(private val context: Context) {

    data class DiscoveryState(
        val scanning: Boolean = false,
        val advertising: Boolean = false,
        val peers: List<Peer> = emptyList(),
        val isolationSuspected: Boolean = false,
        val error: String? = null,
    )

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun advertise(port: Int) {
        val manager = nsd ?: return
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "Filet-" + PairedPeers.selfName(context).take(20)
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _state.value = _state.value.copy(advertising = true)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                _state.value = _state.value.copy(advertising = false, error = "Could not announce on this network.")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _state.value = _state.value.copy(advertising = false)
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registrationListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopAdvertising() {
        val manager = nsd ?: return
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        registrationListener = null
        _state.value = _state.value.copy(advertising = false)
    }

    fun startScan() {
        val manager = nsd ?: run {
            _state.value = _state.value.copy(error = "This device has no network service discovery.")
            return
        }
        stopScan()
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
                // Our own advertisement comes back to us; showing yourself in the peer list
                // is the first thing every implementation gets wrong.
                if (info.serviceName.contains(PairedPeers.selfName(context))) return
                resolve(manager, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _state.value = _state.value.copy(
                    peers = _state.value.peers.filterNot { it.name == info.serviceName }
                )
            }
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopScan() {
        val manager = nsd ?: return
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discoveryListener = null
        _state.value = _state.value.copy(scanning = false)
    }

    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val uuid = resolved.serviceName
                val peer = Peer(
                    uuid = uuid,
                    name = resolved.serviceName.removePrefix("Filet-"),
                    model = "",
                    host = host,
                    port = resolved.port,
                    foundBy = if (PairedPeers.isTrusted(context, uuid)) FoundBy.PAIRED else FoundBy.MDNS,
                    lastSeen = System.currentTimeMillis(),
                    paired = PairedPeers.isTrusted(context, uuid),
                )
                _state.value = _state.value.copy(
                    peers = (_state.value.peers.filterNot { it.uuid == peer.uuid } + peer)
                        .sortedBy { it.label.lowercase() }
                )
            }
        })
    }

    /** Record a peer typed in by hand, for the case where discovery cannot work at all. */
    fun addManual(host: String, port: Int) {
        val peer = Peer(
            uuid = "manual:$host:$port",
            name = host,
            model = "",
            host = host,
            port = port,
            foundBy = FoundBy.MANUAL,
            lastSeen = System.currentTimeMillis(),
            paired = false,
        )
        _state.value = _state.value.copy(
            peers = (_state.value.peers.filterNot { it.uuid == peer.uuid } + peer)
        )
    }

    fun markUnreachable(uuid: String) {
        _state.value = _state.value.copy(
            peers = _state.value.peers.map { if (it.uuid == uuid) it.copy(reachable = false) else it },
            // Found on mDNS but refusing TCP is the signature of AP client isolation, not of
            // a broken app - and saying so is the difference between a bug report and a fix.
            isolationSuspected = true,
        )
    }

    private companion object { const val SERVICE_TYPE = "_filet._tcp." }
}
