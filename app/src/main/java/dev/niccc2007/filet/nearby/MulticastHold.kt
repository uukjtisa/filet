package dev.niccc2007.filet.nearby

import android.content.Context
import android.net.wifi.WifiManager

/**
 * The Wi-Fi multicast lock, held while anything in the process is listening or announcing.
 *
 * Bug identified here: the three mDNS users (`DavBeacon`, `ServerScan`, `NearbyDiscovery`)
 * were written on the belief that `NsdManager` holds this lock itself. On an Android 12 phone it
 * does not, and nothing else did: `dumpsys wifi` listed no multicast lock held while the scan
 * ran. Its queries went out, but what the phone RECEIVES passes the Wi-Fi packet filter first,
 * and that filter drops multicast unless some app holds the lock. Captured on the network: a
 * second phone answering every query with its full record, and this phone's next query still
 * listing only its own service as a known answer - the replies never reached its responder.
 * Whether the platform takes the lock on its own varies by release, which is how one build saw
 * every host on one phone and none on another.
 *
 * One lock for the process, by owner, rather than one per caller: a set of named owners makes
 * holding idempotent, so a caller that stops twice or a heal that restarts a listener cannot
 * release somebody else's hold, and an unbalanced release - which throws on a counted lock - is
 * not possible to write.
 */
object MulticastHold {

    private val owners = HashSet<String>()
    private var lock: WifiManager.MulticastLock? = null

    /** Whether [owner] wants multicast delivered. The lock is held while any owner does. */
    @Synchronized
    fun hold(context: Context, owner: String, on: Boolean) {
        val changed = if (on) owners.add(owner) else owners.remove(owner)
        if (!changed) return
        if (owners.isEmpty()) {
            lock?.let { l -> if (l.isHeld) runCatching { l.release() } }
            return
        }
        val l = lock ?: runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("filet-discovery")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()?.also { lock = it } ?: return
        if (!l.isHeld) runCatching { l.acquire() }
    }

    /** Who is holding it, for diagnostics. */
    @Synchronized
    fun holders(): Set<String> = owners.toSet()
}
