package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.net.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One object the UI talks to for everything Nearby.
 *
 * Sharing is a *mode you enter*, and this is what it means to enter it: create the folders,
 * start the server, announce on mDNS, and put a visible foreground notification up. Stopping
 * reverses all four. Nothing here runs at startup.
 */
class NearbyManager(
    private val context: Context,
    private val vfs: Vfs,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    val shared = SharedSet(context, vfs, prefs)
    val discovery = NearbyDiscovery(context)

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val server = NearbyHttpServer(
        context = context,
        vfs = vfs,
        shared = shared,
        deviceName = { PairedPeers.selfName(context) },
        onEvent = { _state.value = it },
    )

    val selfName: String get() = PairedPeers.selfName(context)
    fun setSelfName(name: String) = PairedPeers.setSelfName(context, name)

    fun isRunning() = server.isRunning()
    fun idleExpired() = server.idleExpired()

    /**
     * Why sharing cannot start right now, or null when it can.
     *
     * Checked *before* anything is started, and surfaced as a disabled button with the reason
     * under it. Starting a server on a device with no usable address used to print a mobile
     * address nobody could reach, or nothing at all, and then the foreground service died on
     * its own timeout - a crash, for a situation the app could see coming.
     */
    fun blockedReason(): String? = when {
        NetAddresses.reachable().isEmpty() ->
            "Join a Wi-Fi network or turn on your hotspot first — there is no local network to share over."
        else -> null
    }

    fun start() {
        if (server.isRunning()) return
        if (blockedReason() != null) return
        scope.launch {
            shared.ensureFolders()
            server.pinRequired = prefs.getBool(KEY_PIN, true)
            server.uploadsAllowed = prefs.getBool(KEY_UPLOADS, false)
            server.idleStopMinutes = prefs.getLong(KEY_IDLE, 30L).toInt()
            server.fixedPin = fixedPin()
            runCatching { server.start() }
                .onSuccess {
                    _state.value = it
                    runCatching { discovery.advertise(it.port) }
                    NearbyService.start(context)
                }
                .onFailure { _state.value = ServerState(running = false) }
        }
    }

    // ── the code on the lock screen ──

    val grants: AccessGrants get() = server.grants

    /** Null means "a fresh random code each session", which is the default. */
    fun fixedPin(): String? = prefs.getString(KEY_FIXED_PIN)?.takeIf { it.length == 6 }

    fun setFixedPin(value: String?) {
        val clean = value?.filter(Char::isDigit)?.take(6)?.takeIf { it.length == 6 }
        prefs.putString(KEY_FIXED_PIN, clean ?: "")
        server.setPin(clean)
        _state.value = server.state()
    }

    fun revokeGrant(token: String) {
        server.grants.revoke(token)
        _state.value = server.state()
    }

    fun revokeAllGrants() {
        server.grants.revokeAll()
        _state.value = server.state()
    }

    fun setGrantLifetimeMinutes(minutes: Int) {
        server.grants.defaultLifetimeMinutes = minutes
        _state.value = server.state()
    }

    fun setGrantIdleMinutes(minutes: Int) {
        server.grants.defaultIdleMinutes = minutes
        _state.value = server.state()
    }

    fun stop() {
        discovery.stopAdvertising()
        server.stop()
        _state.value = server.state()
        NearbyService.stop(context)
    }

    fun setPinRequired(required: Boolean) {
        server.pinRequired = required
        prefs.putBool(KEY_PIN, required)
        _state.value = server.state()
    }

    fun setUploadsAllowed(allowed: Boolean) {
        server.uploadsAllowed = allowed
        prefs.putBool(KEY_UPLOADS, allowed)
        _state.value = server.state()
    }

    fun setIdleMinutes(minutes: Int) {
        server.idleStopMinutes = minutes
        prefs.putLong(KEY_IDLE, minutes.toLong())
        _state.value = server.state()
    }

    fun kick(address: String) = server.kick(address)

    fun startScan() = discovery.startScan()
    fun stopScan() = discovery.stopScan()

    /**
     * Pair with a peer.
     *
     * Both sides show a four-digit code derived from both UUIDs and the shared token. The
     * user compares them by eye; if they differ, something is between the two devices.
     */
    fun beginPairing(peer: Peer): Pairing {
        val token = PairedPeers.newToken()
        val self = PairedPeers.selfUuid(context)
        return Pairing(peer, token, PairedPeers.shortCode(self, peer.uuid, token))
    }

    fun confirmPairing(pairing: Pairing) {
        PairedPeers.remember(context, pairing.peer.uuid, pairing.peer.label, pairing.token)
    }

    fun forget(uuid: String) = PairedPeers.forget(context, uuid)

    /** What [dev.niccc2007.filet.vfs.provider.net.PeerProvider] needs to reach a peer. */
    fun endpoints(): List<PeerEndpoint> = discovery.state.value.peers.map { p ->
        PeerEndpoint(
            uuid = p.uuid,
            label = p.label,
            host = p.host,
            port = p.port,
            token = PairedPeers.tokenFor(context, p.uuid),
        )
    }

    private companion object {
        const val KEY_PIN = "nearby.pin"
        const val KEY_UPLOADS = "nearby.uploads"
        const val KEY_IDLE = "nearby.idle"
        /** Empty string means "random each session", which is the default. */
        const val KEY_FIXED_PIN = "nearby.fixedPin"
    }
}

data class Pairing(val peer: Peer, val token: String, val code: String)
