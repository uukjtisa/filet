package dev.niccc2007.filet.link

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a Filet link or QR can carry. One entry per kind, and every reader decides each one in an
 * exhaustive `when`, so a kind added here cannot exist without a screen that handles it.
 *
 * Decision: a registry rather than one format per feature. A network drive is the first thing to
 * travel this way, and phone to phone, file links, settings presets, playlists and sharing over
 * the internet are planned to follow; each of those is a new entry here and a new branch in the
 * handler, never a second parser or a second QR drawer.
 */
enum class LinkKind(val path: String, val label: String) {
    DRIVE("drive", "Network drive"),

    /** Files sent over the internet with Filet Portal: where the sealed manifest is, and its key. */
    PORTAL("portal", "Filet Portal"),
}

/**
 * A Filet link: a [kind] and its fields, written two ways.
 *
 * - [web] is an https link on the project's site. Every camera app opens an https link; with
 *   Filet installed Android hands it to the app, and without it the page offers the download.
 *   Everything after `#` is the fragment, which a browser never sends to a server, so an access
 *   code inside one is not logged anywhere on the way.
 * - [app] is the `filet://` form, for places that already know Filet is there.
 *
 * Both carry a version. A link from a newer Filet with fields this one cannot read parses to null
 * and is shown as a plain link, whose page says to update, rather than half-understood.
 */
data class FiletLink(val kind: LinkKind, val fields: Map<String, String>) {

    fun web(): String = "$WEB#${body()}"

    fun app(): String = "$SCHEME://${body()}"

    private fun body(): String =
        kind.path + "?" + (listOf("v" to VERSION.toString()) + fields.toList())
            .joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    companion object {
        const val WEB = "https://uukjtisa.github.io/filet/l"
        const val SCHEME = "filet"
        const val VERSION = 1

        /**
         * Whether [text] is addressed to Filet at all, readable or not.
         *
         * Separate from [parse] because the two answers differ for a link from a newer Filet:
         * that one is Filet's, so it must not be opened as a web page or a file, and it cannot be
         * read, so the answer to it is "update Filet" rather than nothing.
         */
        fun claims(text: String): Boolean {
            val t = text.trim()
            return t.startsWith(WEB) || t.startsWith("$SCHEME://", ignoreCase = true)
        }

        fun parse(text: String): FiletLink? {
            val t = text.trim()
            val body = when {
                t.startsWith("$WEB#") -> t.substringAfter('#')
                t.startsWith("$WEB/#") -> t.substringAfter('#')
                t.startsWith("$SCHEME://", ignoreCase = true) -> t.substring(SCHEME.length + 3)
                else -> return null
            }
            val kind = LinkKind.entries.firstOrNull { it.path == body.substringBefore('?') } ?: return null
            val fields = body.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                dec(it.substringBefore('=')) to dec(it.substringAfter('='))
            }
            val v = fields["v"]?.toIntOrNull() ?: return null
            if (v < 1 || v > VERSION) return null
            return FiletLink(kind, fields - "v")
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/**
 * A network drive as a link carries it: everything needed to add it, and the [deviceId] that
 * lets a second scan recognise the same drive at another address.
 *
 * [share] is the path the server selects the share by - for a Filet host, its base path and the
 * access code, `a/<code>`. Kept as the one value the saved remote stores, so nothing has to split
 * and rejoin it on the way in.
 */
data class DriveLink(
    val name: String,
    val deviceId: String,
    val hosts: List<String>,
    val port: Int,
    val share: String,
    val tls: Boolean = false,
) {
    fun link(): FiletLink = FiletLink(
        LinkKind.DRIVE,
        buildMap {
            put("n", name)
            if (deviceId.isNotEmpty()) put("d", deviceId)
            put("h", hosts.joinToString(","))
            put("p", port.toString())
            put("s", share)
            if (tls) put("t", "1")
        },
    )

    companion object {
        /**
         * The drive in [link], or null where anything in it is not what a drive can be.
         *
         * Strict, because a scanned code is input from anywhere: a host is an address or a name
         * and nothing else, so a link cannot smuggle a path, a scheme or a second URL into the
         * remote it creates.
         */
        fun of(link: FiletLink): DriveLink? {
            if (link.kind != LinkKind.DRIVE) return null
            val f = link.fields
            val hosts = f["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty() || hosts.any { !HOST.matches(it) }) return null
            val port = f["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val share = f["s"].orEmpty().trim('/')
            if (share.isEmpty() || !SHARE.matches(share)) return null
            val id = f["d"].orEmpty()
            if (id.isNotEmpty() && !ID.matches(id)) return null
            val name = f["n"].orEmpty().trim().take(80).ifEmpty { hosts.first() }
            return DriveLink(name, id, hosts, port, share, f["t"] == "1")
        }

        // No IPv6 zone (`%wlan0`): it names the sender's interface, meaningless to the scanner.
        private val HOST = Regex("""^[A-Za-z0-9.\-]{1,253}$|^\[?[0-9A-Fa-f:.]{2,45}]?$""")
        private val SHARE = Regex("""^[A-Za-z0-9_\-]+(/[A-Za-z0-9_\-]+)*$""")
        private val ID = Regex("""^[A-Za-z0-9_\-]{1,64}$""")
    }
}

/**
 * A Filet Portal send as its link carries it: the address of the sealed manifest on a file host,
 * and the key that opens it. The key travels only after the `#`, so the site the link points at
 * never receives it, and the host holding the manifest never sees it.
 */
data class PortalLink(val manifest: String, val key: String) {
    fun link(): FiletLink = FiletLink(LinkKind.PORTAL, mapOf("m" to manifest, "k" to key))

    companion object {
        /**
         * The send in [link], or null. Only an https address and a key of the right shape pass;
         * which hosts are allowed is the app's call, made where the host list lives.
         */
        fun of(link: FiletLink): PortalLink? {
            if (link.kind != LinkKind.PORTAL) return null
            val m = link.fields["m"].orEmpty().trim()
            val k = link.fields["k"].orEmpty().trim()
            if (!m.startsWith("https://") || m.length > 512 || m.any { it.isWhitespace() }) return null
            if (!KEY.matches(k)) return null
            return PortalLink(m, k)
        }

        private val KEY = Regex("""^[A-Za-z0-9_\-]{43}$""")
    }
}
