package dev.niccc2007.filet.webdav

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The announcement, as a contract both ends can read.
 *
 * ## Why the strings live here and not in the thing that broadcasts them
 *
 * A phone announces over `NsdManager` and a PC announces over JmDNS, because Android has a
 * service-discovery API and a desktop JVM has none. Two platforms, two libraries, and that is
 * unavoidable - but the bytes on the wire are one protocol and there is no version of this where
 * they are allowed to differ. A service type spelled `_filet-dav._tcp` in one place and
 * `_filet-dav._tcp.` in the other produces two networks that cannot see each other, with nothing
 * to read and no error to report. The same goes for every TXT key: a listener reading `write`
 * where the announcer wrote `w` sees a read-only share and refuses writes that would have worked.
 *
 * So the wire format is declared once, here, in the module both ends already depend on. A new
 * field is added in one place and both sides have it; it cannot be added to one and forgotten in
 * the other, which is the failure this file exists to make impossible.
 */
object DavDiscovery {

    /**
     * Its own type, not Nearby's.
     *
     * `_filet._tcp.` means "this device is sharing files over Nearby" and leads to a peer browsed
     * in place. This means "this device is mountable as a drive", which has a different port, a
     * different credential and a different lifetime. One service type answering two questions is
     * how a client ends up connecting to the wrong port.
     *
     * The trailing dot is part of it. DNS-SD treats the type as a fully qualified name and some
     * resolvers will not match without it.
     */
    const val SERVICE_TYPE = "_filet-dav._tcp."

    /** The base path, `/a/` - the part of `/a/<code>` that is not a secret. */
    const val ATTR_PATH = "path"

    /** "Whole phone", "Filet/Shared" or a folder name, so a row can say what would be mounted. */
    const val ATTR_SCOPE = "scope"

    /** `"1"` when the far end said it would accept writes. Advisory; every request re-checks. */
    const val ATTR_WRITE = "w"

    /**
     * A stable id for the device advertising, so the SAME device on a different network is
     * recognisable as the same device.
     *
     * Without it, identity is the address - which is exactly the assumption that made one tablet
     * into two saved entries, one per network. The address is the one thing about a host that is
     * guaranteed to change.
     *
     * It is not a secret and carries nothing about the device: a random value made once and kept,
     * which is all that is needed to answer "is this the box I already know".
     */
    const val ATTR_ID = "id"

    /** What every advertised name starts with, and what a listener strips back off. */
    const val NAME_PREFIX = "Filet-"

    /**
     * The advertised service name.
     *
     * The label is in the name because the name is the only field a resolver is guaranteed to
     * hand back before the TXT records arrive. Both parts are clipped, because a DNS-SD instance
     * name has a 63-byte ceiling and an over-long one is refused by the registrar rather than
     * truncated for you.
     */
    fun serviceName(self: String, label: String): String =
        NAME_PREFIX + self.take(20) + "-" + label.take(24)

    /** The display name inside an advertised service name. */
    fun displayName(serviceName: String): String = serviceName.removePrefix(NAME_PREFIX)

    /**
     * Which of a resolved service's addresses to actually use.
     *
     * Pure, and shared by both ends, because it is a decision that can be wrong and was: a
     * resolver hands back whichever address it settled on, and on a device with more than one
     * interface that is frequently the wrong one - a link-local IPv6, or an interface on a
     * different subnet. The host then arrives looking plausible and connects to nothing.
     *
     * A routable IPv4 first, any IPv4 second, and only then whatever was offered. Link-local
     * `169.254.x.x` and IPv6 `fe80::` are dropped outright - they mean the interface never got a
     * lease - but they are kept as a last resort over returning nothing, because a name in a list
     * that fails to connect is still more use than a host that silently never appeared.
     */
    fun pickAddress(candidates: List<InetAddress>): String? {
        if (candidates.isEmpty()) return null
        val usable = candidates.filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
        return usable.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            ?: usable.firstOrNull { it is Inet4Address }?.hostAddress
            ?: usable.firstOrNull()?.hostAddress
            ?: candidates.firstOrNull()?.hostAddress
    }
}

/**
 * One device heard advertising a mountable share.
 *
 * Shared between the phone and the PC on purpose. The two show it differently - the phone draws a
 * row in Home and in Remotes, the PC draws a row with a Mount button - but what was heard is the
 * same thing, and giving each end its own type would mean two places to add a field to and two
 * `key` rules to keep in step.
 */
data class DavHost(
    /** What the other device calls itself, as it advertised. */
    val name: String,
    val host: String,
    val port: Int,
    /** `/a/<code>` without the code - the part of the path that is not a secret. */
    val basePath: String,
    /** "Whole phone", "Filet/Shared" or a folder name, so the row says what would be mounted. */
    val scope: String,
    /** Whether the far end said it would accept writes. Advisory; it re-checks per request. */
    val writable: Boolean = false,
    /**
     * The advertising device's stable id, or empty from a host too old to send one.
     *
     * Empty is handled as "cannot prove who this is" everywhere it matters, rather than as a
     * value that happens to match other empties.
     */
    val deviceId: String = "",
) {
    /**
     * A stable identity, so re-resolving does not duplicate the row.
     *
     * The label is part of it because one device can offer several shares on ONE port - they
     * differ only by their code, which is deliberately not broadcast. Keying on host:port alone
     * would collapse them into one row and hide all but the last.
     */
    val key: String get() = "$host:$port/$name"
}

/**
 * Something that can put a share on the network and take it off again.
 *
 * [WebDavServer] holds one of these and knows nothing else about discovery. That is the whole
 * reason it exists: the server is the same code on a phone and on a PC, and the announcement is
 * the one part that cannot be - `NsdManager` on one, JmDNS on the other. An interface the server
 * calls through is what keeps that difference from reaching the server at all.
 *
 * Both implementations are expected to swallow their own failures rather than throw. A share that
 * is listening and not announced still works; it is just quiet, which is the state hosting was in
 * before any of this existed.
 */
interface DavAnnouncer {

    /**
     * Announce one share.
     *
     * Per share rather than once per device, because shares are separate things to mount -
     * different roots, different permissions - and a single announcement would make a device
     * offering three of them look like it offered one.
     *
     * @param basePath always `/a/`. The code that follows it is not broadcast.
     */
    fun advertise(
        id: Long,
        label: String,
        port: Int,
        basePath: String,
        scope: String,
        writable: Boolean = false,
    )

    /** Withdraw one share's announcement. */
    fun stopAdvertising(id: Long)
}
