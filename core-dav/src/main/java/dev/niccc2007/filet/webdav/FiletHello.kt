package dev.niccc2007.filet.webdav

import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /.well-known/filet`: who answers on this port, for a client that found it without mDNS.
 *
 * mDNS is the first way devices find each other, and on plenty of networks it does not get
 * through - guest Wi-Fi, routers that drop multicast between bands, a phone that has just moved
 * to a new address while the old record is still cached. A client that already knows a device's
 * id can then knock on likely addresses and ask. This is the answer: the same facts the beacon
 * already broadcasts, so a sweep learns nothing the network was not already being told.
 *
 * **Never a code and never a path.** The code is the password; the path would say where on the
 * disk a share lives. The answer is built from a share's label, its root's display name and its
 * write flag, and from nothing else, so neither can reach it by accident.
 *
 * Unauthenticated, outside `/a/`, and cheap: no disk, no index, so a sweep's short timeout is
 * met. Any other `/.well-known/` name is a 404, so this does not become a general prefix.
 */
object FiletHello {
    const val PATH = "/.well-known/filet"
    const val VERSION = 1

    /** Who this host is, supplied by the app: the TXT id, the beacon's name, and `phone` or `pc`. */
    data class Identity(val id: String, val name: String, val kind: String)

    enum class Route { HELLO, NOT_FOUND }

    /** What [target] asks for: this answer, a 404 for another well-known name, or null for neither. */
    fun route(target: String): Route? {
        val path = target.substringBefore('?').substringBefore('#')
        if (path == PATH || path == "$PATH/") return Route.HELLO
        if (path == "/.well-known" || path.startsWith("/.well-known/")) return Route.NOT_FOUND
        return null
    }

    /**
     * The answer body.
     *
     * [shares] are the shares live on this port, each with whether it is writable this session;
     * see `WebDavServer.LiveShare.writable`, which can differ from the stored setting.
     */
    fun json(me: Identity, port: Int, shares: List<Pair<DavShare, Boolean>>): String =
        JSONObject().apply {
            put("v", VERSION)
            put("id", me.id)
            put("name", me.name)
            put("kind", me.kind)
            put("port", port)
            put("shares", JSONArray().apply {
                for ((s, writable) in shares) put(JSONObject().apply {
                    put("label", s.label)
                    put("scope", s.rootLabel)
                    put("w", writable)
                })
            })
        }.toString()
}
