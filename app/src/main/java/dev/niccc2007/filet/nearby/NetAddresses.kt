package dev.niccc2007.filet.nearby

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * One address this device can be reached on, and how likely that is to be useful.
 *
 * @param kind what the interface is, in words the user recognises. The interface name is
 *   kept too, because on a phone with Wi-Fi, a hotspot and mobile data up at once, "which
 *   one" is a real question and `wlan0` answers it.
 */
data class NetAddress(
    val ip: String,
    val iface: String,
    val kind: Kind,
) {
    enum class Kind(val label: String, val reachable: Boolean) {
        /** Joined to a router. The one a laptop on the same router can reach. */
        WIFI("Wi-Fi", true),

        /** This phone IS the network. A laptop joined to it can reach this. */
        HOTSPOT("Hotspot", true),

        /** USB or dock ethernet, and `adb reverse` also lands here. */
        WIRED("Wired", true),

        /**
         * Mobile data. Behind carrier-grade NAT essentially always, so a laptop cannot reach
         * it even though the address looks perfectly normal - which is exactly why it must be
         * labelled rather than silently offered as "the" URL.
         */
        MOBILE("Mobile data", false),

        OTHER("Other", true),
    }

    fun urlFor(port: Int): String = "http://$ip:$port"
}

/**
 * Every IPv4 address this device currently holds, best candidate first.
 *
 * The old code took the **first** non-loopback address it found across all interfaces. On a
 * phone with mobile data up, that is very often `rmnet_data0` - a carrier-NAT address that
 * looks fine, that the app prints confidently, and that no other machine on the planet can
 * open. Sorting by kind and showing every one is the fix: the user picks the address that
 * matches the network their laptop is on, instead of guessing why nothing responds.
 *
 * IPv6 is left out on purpose. Link-local v6 needs a zone index (`%wlan0`) that browsers
 * handle inconsistently, and a URL that half works is worse than one address fewer.
 */
object NetAddresses {

    fun all(): List<NetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { addr ->
                        addr.hostAddress?.let { NetAddress(it, iface.name, kindOf(iface.name)) }
                    }
            }
            .distinctBy { it.ip }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.ip }))
    }.getOrDefault(emptyList())

    /** Addresses another machine has a real chance of reaching. */
    fun reachable(): List<NetAddress> = all().filter { it.kind.reachable }

    /** The one to print first, or null when this device is not on a usable network at all. */
    fun primary(): NetAddress? = reachable().firstOrNull()

    /**
     * Interface names are not standardised, but the prefixes are stable enough across vendors
     * to be worth reading - and the cost of being wrong is a mislabelled row, not a failure.
     */
    private fun kindOf(name: String): NetAddress.Kind {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("wifi") -> NetAddress.Kind.WIFI
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> NetAddress.Kind.HOTSPOT
            n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis") -> NetAddress.Kind.WIRED
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                n.startsWith("seth") || n.startsWith("v4-rmnet") -> NetAddress.Kind.MOBILE
            else -> NetAddress.Kind.OTHER
        }
    }
}
