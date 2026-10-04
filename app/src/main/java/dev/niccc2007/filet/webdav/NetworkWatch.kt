package dev.niccc2007.filet.webdav

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices when this device moves to a different network, and says so once.
 *
 * ## The fault this fixes
 *
 * Switching a hosting device from mobile data to Wi-Fi left it invisible to everything looking for
 * it. Nothing was watching the network, so:
 *
 * - the **mDNS announcement** kept naming the address it was registered with, which no longer
 *   existed - the record has to be withdrawn and made again, which is exactly what stopping and
 *   starting hosting by hand did;
 * - the **discovery listener** on the other device kept a stale view, so the host never came back
 *   into its list;
 * - and the **saved remote** therefore never learned the new address, because learning happens
 *   from an announcement that was not arriving.
 *
 * One signal fixes all three, which is why it is one class and not three. [NetIdentity] decides
 * what counts as a move, with its own test.
 *
 * ## Why the default network, and why it is registered for the whole process
 *
 * `registerDefaultNetworkCallback` follows the network the system would actually use, which is the
 * one an announcement should name. A callback per transport would report both Wi-Fi and mobile and
 * leave this class choosing between them - re-deriving a decision the platform has already made.
 *
 * It is registered once and never unregistered: a share can be up while the app is in the
 * background, so a watcher that stopped with the UI would stop at exactly the moment a phone is in
 * a pocket moving between networks. One callback costs nothing; missing the change costs the
 * feature.
 */
class NetworkWatch(private val context: Context) {

    private val _moves = MutableStateFlow(0L)

    /**
     * Bumped once per move to a different network.
     *
     * A counter rather than the [NetId] itself, so a collector cannot accidentally treat two moves
     * that happen to land on the same network as one event.
     */
    val moves: StateFlow<Long> = _moves.asStateFlow()

    /** What the last callback described, for comparison. Null until the first one arrives. */
    @Volatile
    private var current: NetId? = null

    private val cm: ConnectivityManager? = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        val manager = cm ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sample(network)
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) =
                sample(network, props)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                sample(network)

            override fun onLost(network: Network) = offline()
        }
        callback = cb
        // A plain default-network callback would be enough, but the request form also works on
        // API 26, which the no-argument overload does not.
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        }.onFailure { callback = null }
    }

    private fun sample(network: Network, props: LinkProperties? = null) {
        val manager = cm ?: return
        val link = props ?: runCatching { manager.getLinkProperties(network) }.getOrNull()
        val addresses = link?.linkAddresses
            ?.mapNotNull { it.address?.hostAddress }
            ?.filterNot { it.startsWith("127.") || it == "::1" }
            .orEmpty()
        publish(NetId(runCatching { network.networkHandle }.getOrDefault(0L), addresses))
    }

    private fun offline() = publish(NetId.NONE)

    @Synchronized
    private fun publish(now: NetId) {
        val before = current
        current = now
        if (NetIdentity.changed(before, now)) _moves.value = _moves.value + 1
    }
}
