package dev.niccc2007.filet.webdav

/**
 * Which network this device is on, and whether that has actually changed.
 *
 * ## Why a decision this small gets its own file and a test
 *
 * `ConnectivityManager` fires constantly. A single Wi-Fi association produces several callbacks
 * - available, capabilities changed twice, link properties changed - and a phone with mobile data
 * alongside Wi-Fi produces them for both. Acting on every callback means tearing down and
 * rebuilding every mDNS announcement and the discovery listener several times per association,
 * which is worse than not reacting at all: a re-registration storm is how a share flickers in and
 * out of somebody else's list.
 *
 * Acting on too few is the bug this exists to fix. Switching a tablet from mobile data to Wi-Fi
 * keeps the process alive and the socket bound, so nothing noticed - the announcement kept naming
 * an address that had stopped existing, and the share was invisible until hosting was stopped and
 * started by hand.
 *
 * So the question is asked once, in one place: **is this a different network, or the same one
 * talking?**
 */
data class NetId(
    /** The platform's own handle for the network, which changes when the network does. */
    val handle: Long,
    /**
     * The addresses this device holds on it.
     *
     * Part of the identity because the handle alone is not enough: a Wi-Fi network that drops and
     * comes back, or a DHCP lease that renews to a different address, can keep its handle while
     * every address on it changes. The address is what an announcement carries, so an address
     * change is a change.
     */
    val addresses: List<String>,
) {
    /** Nothing reachable. Not a network to announce on, but a state to remember being in. */
    val offline: Boolean get() = addresses.isEmpty()

    companion object {
        val NONE = NetId(0L, emptyList())
    }
}

object NetIdentity {

    /**
     * Whether [now] is worth re-announcing and re-scanning for, compared with [before].
     *
     * Three rules, and each of them is a failure that has happened:
     *
     * 1. **Going offline is not a change to act on.** There is nothing to announce on and no
     *    interface to scan from, and tearing everything down at the moment the network blinks
     *    means the recovery has to rebuild from nothing rather than simply re-registering. It is
     *    still recorded, so coming back counts.
     * 2. **Coming back from offline is a change**, even onto the same network - the registration
     *    made before the drop is bound to an interface that went away.
     * 3. **The same network re-describing itself is not a change.** Same handle, same addresses,
     *    however many callbacks it arrives in.
     */
    fun changed(before: NetId?, now: NetId): Boolean {
        if (now.offline) return false
        if (before == null || before.offline) return true
        if (before.handle != now.handle) return true
        return !sameAddresses(before.addresses, now.addresses)
    }

    /**
     * Address sets compared as sets, not as lists.
     *
     * The platform hands them back in whatever order it holds them, and an IPv6 temporary address
     * being re-randomised reorders the list without changing where the device can be reached.
     * Ordering alone used to read as a network change on every callback.
     */
    private fun sameAddresses(a: List<String>, b: List<String>): Boolean =
        a.map { it.lowercase() }.toSet() == b.map { it.lowercase() }.toSet()
}
