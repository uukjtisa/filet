package dev.niccc2007.filet.home

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.webdav.DavBeacon

/**
 * Network places, for the Home tab.
 *
 * Two different things end up in one list and they have genuinely different lifetimes:
 *
 *  - a **saved place**, which exists because somebody added it and stays until they remove it;
 *  - a **discovered host**, which exists only while something on the network is advertising and
 *    vanishes the moment it stops.
 *
 * Merging them is the point - from the reader's side both are "somewhere I can open" - but the
 * merge has to keep the difference, because one can be opened straight away and the other needs a
 * code first.
 */
data class NetworkPlace(
    val title: String,
    val detail: String,
    val state: PlaceState,
    /** The saved connection, when there is one. Null for a host that has only been discovered. */
    val connection: NetConnection?,
    /** The discovered host, when it is on the air. */
    val host: DavBeacon.Host?,
) {
    /** Openable right now. A discovered host that has never been added needs its code first. */
    val openable: Boolean get() = connection != null
}

/**
 * Whether a place is reachable.
 *
 * Three states, not two, and that is the whole care taken here. A dot drawn from stored settings
 * says "configured", not "active" - and a red dot on a working NAS is worse than no dot at all.
 * Nothing here polls, so the only thing actually known is whether something is advertising on the
 * network right now.
 */
enum class PlaceState {
    /** Advertising on the LAN at this moment. */
    Live,

    /** Saved, and nothing is advertising that matches it. Not the same as being down. */
    Unknown,
}

object NetworkPlaces {

    /**
     * Whether [c] is the place [h] is advertising.
     *
     * Host and port, because that pair is what an address resolves to. The path is deliberately
     * NOT compared: a saved place carries `a/<code>` and the announcement carries only `a/`, since
     * the code is never broadcast - so comparing paths would never match anything.
     */
    fun matches(c: NetConnection, h: DavBeacon.Host): Boolean =
        c.host.equals(h.host, ignoreCase = true) && c.port == h.port

    /**
     * The list to draw: saved places first, then anything discovered that is not already saved.
     *
     * Saved first because they are the ones that can be opened with one tap. A discovered host
     * that is already saved does not appear twice - it lights up the saved row instead, which is
     * the honest rendering of "this thing you added is on right now".
     */
    fun merge(
        saved: List<NetConnection>,
        discovered: List<DavBeacon.Host>,
    ): List<NetworkPlace> {
        val out = ArrayList<NetworkPlace>(saved.size + discovered.size)

        for (c in saved) {
            val live = discovered.firstOrNull { matches(c, it) }
            out += NetworkPlace(
                title = c.label.ifBlank { c.host },
                detail = buildString {
                    append(c.protocol.label)
                    if (c.host.isNotBlank()) { append(" · "); append(c.host) }
                    if (live != null) append(" · on now")
                },
                state = if (live != null) PlaceState.Live else PlaceState.Unknown,
                connection = c,
                host = live,
            )
        }

        for (h in discovered) {
            if (saved.any { matches(it, h) }) continue
            out += NetworkPlace(
                title = h.name,
                // Says what it is and what is still needed, because tapping it cannot open
                // anything until a code has been supplied.
                detail = buildString {
                    append(h.host)
                    append(':')
                    append(h.port)
                    if (h.scope.isNotEmpty()) { append(" · "); append(h.scope) }
                    append(" · needs a code")
                },
                state = PlaceState.Live,
                connection = null,
                host = h,
            )
        }

        return out
    }

    /** Whether the Home section should be drawn at all. */
    fun hasAnything(saved: List<NetConnection>, discovered: List<DavBeacon.Host>): Boolean =
        saved.isNotEmpty() || discovered.isNotEmpty()
}
