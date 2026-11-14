package dev.niccc2007.filet.nearby

import java.net.URI
import java.net.URLDecoder

/**
 * A fast share's link, as its QR carries it: `http://<phone>:<port>/f/<token>?k=<key>`, or `/zd/`
 * for a folder and `/z` for several files. Any camera opens it in the browser; Filet's own scanner
 * reads it here and fetches the files itself, into Filet/Received.
 *
 * Only on a local network address: a fast share is the same Wi-Fi, and a link elsewhere with the
 * same shape is not one.
 */
object FastLink {
    data class Parsed(val base: String, val host: String, val key: String)

    fun parse(text: String): Parsed? {
        val u = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (u.scheme != "http") return null
        val host = u.host ?: return null
        if (u.port !in 1..65535 || !local(host)) return null
        val path = u.rawPath ?: return null
        if (!(path.startsWith("/f/") || path.startsWith("/zd/") || path == "/z")) return null
        val key = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("k=") }?.removePrefix("k=")
            ?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() } ?: return null
        return Parsed("http://$host:${u.port}", host, key)
    }

    /** A private IPv4 address: 10/8, 172.16/12, 192.168/16, and a hotspot's 192.168.43.x among them. */
    fun local(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
}
