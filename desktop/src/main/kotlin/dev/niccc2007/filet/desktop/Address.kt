package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.webdav.DavShares
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The address as one string - the thing the phone's add form takes in a single box.
 *
 * The shape is `:core-dav`'s, `http://<ip>:<port>/a/<code>`, built by [DavShares.endpoint] so
 * this end and the phone cannot disagree about it. SPEC.md draws `/<share>`; that predates the
 * server this tool hosts, whose share selector is the code.
 */
object Address {

    data class Parsed(val host: String, val port: Int, val code: String)

    /** [host] bracketed when it is IPv6, since `http://fe80::1:8322` is not a URL. */
    fun of(host: String, port: Int, code: String): String =
        DavShares.endpoint(if (host.contains(':') && !host.startsWith("[")) "[$host]" else host, port, code)

    /** The server alone, for the window's one big line when no particular share is meant. */
    fun base(host: String, port: Int): String =
        "http://" + (if (host.contains(':') && !host.startsWith("[")) "[$host]" else host) + ":$port/"

    /**
     * The inverse of [of], or null for anything that is not one.
     *
     * Strict on purpose: the code must be letters and digits, because a pasted address that
     * carries a trailing `/` or a sub-path is a different endpoint and should not quietly mount.
     */
    fun parse(raw: String): Parsed? {
        val s = raw.trim()
        if (!s.startsWith("http://")) return null
        val rest = s.removePrefix("http://")
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val authority = rest.substring(0, slash)
        val path = rest.substring(slash)
        val (host, portText) = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0 || authority.getOrNull(close + 1) != ':') return null
            authority.substring(1, close) to authority.substring(close + 2)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon <= 0) return null
            authority.substring(0, colon) to authority.substring(colon + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (!path.startsWith("/a/")) return null
        val code = path.removePrefix("/a/")
        if (code.isEmpty() || !code.all { it.isLetterOrDigit() }) return null
        if (host.isEmpty()) return null
        return Parsed(host, port, code)
    }
}

/** One IPv4 address this PC holds on a LAN, with the adapter it belongs to. */
data class HostAddress(val ip: String, val prefix: Int, val adapter: String)

/**
 * Which of this PC's addresses to print.
 *
 * Bug identified here: the first version asked the routing table, via a UDP `connect`, which
 * address the OS would send outward from. On a PC with both Ethernet and Wi-Fi up, that is the
 * interface with the lower metric - Ethernet at 192.168.5.13 on one machine, metric 35 against
 * Wi-Fi's 55 - while the phone sits on the Wi-Fi subnet and cannot reach it. The route to the
 * internet says nothing about the route to the phone. The address was printed confidently and was
 * wrong.
 *
 * So the decision is now: an address on the same subnet as a Filet peer actually heard on the
 * network; else the one picked by hand; else the only candidate there is. With several candidates
 * and nothing to tell them apart, there is NO pick, and every site-local address is shown with its
 * adapter's name. Three labelled addresses beat one that is confidently wrong.
 */
object LanAddress {

    /** Every site-local IPv4 on an interface that is up, each with its adapter's display name. */
    fun candidates(): List<HostAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { iface ->
                iface.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        HostAddress(a.hostAddress, ia.networkPrefixLength.toInt(), iface.displayName ?: iface.name)
                    } else null
                }
            }
            .distinctBy { it.ip }
    }.getOrDefault(emptyList())

    /**
     * The address to show as THE address, or null when it cannot be known.
     *
     * Pure, so the rule is tested without a network. [peers] are hosts heard advertising Filet;
     * [chosen] is the address picked by hand, honoured only while the PC still holds it.
     */
    fun choose(candidates: List<HostAddress>, peers: List<String>, chosen: String?): HostAddress? {
        val near = candidates.filter { c -> peers.any { sameSubnet(c, it) } }
        if (near.size == 1) return near[0]
        candidates.firstOrNull { it.ip == chosen }?.let { return it }
        if (near.size > 1) return null
        return candidates.singleOrNull()
    }

    /** Whether [other] is on [c]'s subnet. Non-IPv4 and malformed input answer false. */
    fun sameSubnet(c: HostAddress, other: String): Boolean {
        val a = v4(c.ip) ?: return false
        val b = v4(other) ?: return false
        val p = c.prefix.coerceIn(0, 32)
        val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    private fun v4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (p in parts) {
            val v = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            n = (n shl 8) or v.toLong()
        }
        return n
    }

    /** Every address on every interface that is up, for the headless printout. */
    fun all(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
    }.getOrDefault(emptyList())
}
