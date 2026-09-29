package dev.niccc2007.filet.remotes

import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * Reading an address somebody pasted, instead of asking them to take it apart.
 *
 * ## Why
 *
 * The form asked for a host, a port, a share name, a user and a password. The hosting side gives
 * out **one string** - `http://192.168.1.9:11111/a/tres3`, or the `\\host@port\DavWWWRoot\a\code`
 * form Windows needs. So every single time, somebody had to split an address they already had into
 * five boxes, and guess which part was the "share".
 *
 * The form had too many inputs and nothing leading to what belonged in them, because the fields
 * did not correspond to anything the other end actually shows. A share on the hosting side has a
 * name and an access code; the form asked for a host, a port, a path, a user and a password.
 *
 * So: paste the whole thing, and this takes it apart. The fields stay, filled in, because a
 * parse can be wrong and a person needs to see what it decided.
 *
 * ## What it accepts
 *
 * Every shape the various surfaces hand out, because which one you have depends on where you
 * copied it from and nobody should have to care:
 *
 *     http://192.168.1.9:11111/a/tres3      Filet's own "Mac and Linux" row
 *     \\192.168.1.9@11111\DavWWWRoot\a\tres3  Filet's "Windows" row, pasted back
 *     192.168.1.9:11111/a/tres3             the same thing with the scheme dropped
 *     smb://nas/media                       a NAS's own documentation
 *     \\PC\Shared                           what Windows shows in its address bar
 *     sftp://user@host:2222/                credentials included
 */
data class ParsedAddress(
    val protocol: NetProtocol?,
    val host: String,
    val port: Int?,
    /** The share name for SMB, or the base path for WebDAV. */
    val share: String,
    val user: String = "",
    val tls: Boolean = false,
) {
    val usable: Boolean get() = host.isNotBlank()
}

object RemoteAddress {

    /**
     * Take an address apart, or return null when it is not one.
     *
     * Null rather than a half-filled guess: silently filling two of five fields from something
     * that was not an address at all is worse than leaving the form alone, because the person
     * then has to work out which parts were touched.
     */
    fun parse(raw: String): ParsedAddress? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // The UNC form first, because it is the one Windows hands out and it is not a URL.
        if (text.startsWith("\\\\") || text.startsWith("//")) return parseUnc(text)

        // A scheme, if there is one.
        var rest = text
        var protocol: NetProtocol? = null
        var tls = false
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = rest.substring(0, schemeEnd).lowercase()
            protocol = when (scheme) {
                "smb", "cifs" -> NetProtocol.SMB
                "sftp", "ssh" -> NetProtocol.SFTP
                "ftp" -> NetProtocol.FTP
                "ftps" -> { tls = true; NetProtocol.FTP }
                "dav", "http", "webdav" -> NetProtocol.WEBDAV
                "davs", "https" -> { tls = true; NetProtocol.WEBDAV }
                else -> return null
            }
            rest = rest.substring(schemeEnd + 3)
        }

        // user[:password]@host - the password is deliberately NOT taken from a pasted string.
        // A credential in something copied off a screen or out of a chat is one nobody meant to
        // put in a form, and it would be stored without ever being shown.
        var user = ""
        val at = rest.lastIndexOf('@')
        if (at > 0) {
            user = rest.substring(0, at).substringBefore(':')
            rest = rest.substring(at + 1)
        }

        val slash = rest.indexOf('/')
        val hostPort = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash + 1)

        val (host, port) = splitHostPort(hostPort) ?: return null
        if (!plausibleHost(host)) return null

        // A bare word with nothing else is not an address yet - it is the middle of somebody
        // typing one. A dot, a port, a path or a scheme all say "this is a location"; "ht" on
        // its own says nothing, and reporting it as a host named ht is how the box came to
        // confidently summarise nonsense.
        val looksLikeAddress = protocol != null ||
            port != null ||
            slash >= 0 ||
            host.contains('.') ||
            host.contains(':')
        if (!looksLikeAddress) return null

        return ParsedAddress(
            protocol = protocol,
            host = host,
            port = port,
            share = path.trim('/'),
            user = user,
            tls = tls,
        )
    }

    /**
     * `\\host\share`, and the `\\host@port\DavWWWRoot\a\code` spelling Windows needs for WebDAV.
     *
     * The second is the string Filet's own hosting card prints for Windows, so somebody copying
     * it back into the phone is a perfectly ordinary thing to do.
     */
    private fun parseUnc(raw: String): ParsedAddress? {
        val body = raw.replace('\\', '/').trimStart('/')
        val parts = body.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val hostPart = parts[0]
        // `@port` is how Windows carries a non-default WebDAV port; there is nowhere else in the
        // UNC syntax to put one.
        val host: String
        var port: Int? = null
        val at = hostPart.indexOf('@')
        if (at > 0) {
            host = hostPart.substring(0, at)
            port = hostPart.substring(at + 1).toIntOrNull()
        } else {
            host = hostPart
        }
        if (host.isBlank()) return null

        // DavWWWRoot is Windows' marker for "this is WebDAV", not a folder.
        val rest = parts.drop(1).filterNot { it.equals("DavWWWRoot", ignoreCase = true) }

        // A port, or the marker, means WebDAV. A bare \\host\share is SMB.
        val isDav = port != null || parts.any { it.equals("DavWWWRoot", ignoreCase = true) }

        return ParsedAddress(
            protocol = if (isDav) NetProtocol.WEBDAV else NetProtocol.SMB,
            host = host,
            port = port,
            share = rest.joinToString("/"),
        )
    }

    /**
     * Whether this looks like somewhere, rather than a half-typed address.
     *
     * This box is parsed on every keystroke, so it sees "h", "ht", "http", "http:/" on the way to
     * anything real. Without this, "http:/" parsed as a HOST NAMED "http:" and the form cheerfully
     * reported "Reading it as http:" - a confident summary of nonsense, which is worse than no
     * summary at all.
     *
     * A colon surviving here means the port was not a number, which is what a partly-typed scheme
     * looks like. A bare scheme word on its own is the other half of the same case.
     */
    private fun plausibleHost(h: String): Boolean {
        if (h.isBlank()) return false
        if (h.any { it == '/' || it == '\\' || it.isWhitespace() }) return false
        // A colon is a port separator everywhere except inside an IPv6 literal, which
        // arrives here unbracketed and is nothing but hex digits and colons.
        if (h.contains(':') && !looksIpv6(h)) return false
        if (h.none { it.isLetterOrDigit() }) return false
        // Still typing the scheme.
        if (h.lowercase() in SCHEME_WORDS) return false
        return true
    }

    private fun looksIpv6(h: String): Boolean =
        h.count { it == ':' } >= 2 &&
            h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    private val SCHEME_WORDS = setOf(
        "http", "https", "dav", "davs", "webdav", "smb", "cifs", "sftp", "ssh", "ftp", "ftps",
    )

    private fun splitHostPort(s: String): Pair<String, Int?>? {
        if (s.isBlank()) return null
        // An IPv6 literal is bracketed, and its colons are not port separators.
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            val after = s.substring(close + 1)
            val port = if (after.startsWith(":")) after.drop(1).toIntOrNull() else null
            return host to port
        }
        val colon = s.lastIndexOf(':')
        if (colon < 0) return s to null
        val port = s.substring(colon + 1).toIntOrNull() ?: return s to null
        return s.substring(0, colon) to port
    }

    /**
     * The one string that IS this connection, for the single address box.
     *
     * The inverse of [parse]. A place is an address plus a credential, and the address is not
     * three questions - a host, a port and a path are parts of one string that the other end
     * hands out whole. Splitting them across boxes, and then hiding the port under an "advanced"
     * heading, is how the form became incoherent: the port is not optional, it is part of where
     * the thing is.
     */
    fun compose(
        protocol: NetProtocol,
        host: String,
        port: Int,
        share: String,
        tls: Boolean,
        /** Ends the address with a slash, for a path that is a prefix somebody still completes. */
        trailingSlash: Boolean = false,
    ): String {
        if (host.isBlank()) return ""
        val path = share.trim('/')
        return when (protocol) {
            // SMB has no URL form anybody recognises; the UNC spelling is what Windows shows and
            // what people paste.
            NetProtocol.SMB ->
                "\\\\" + host + (if (path.isEmpty()) "" else "\\" + path.replace('/', '\\'))
            else -> {
                val scheme = when (protocol) {
                    NetProtocol.WEBDAV -> if (tls) "https" else "http"
                    NetProtocol.SFTP -> "sftp"
                    NetProtocol.FTP -> if (tls) "ftps" else "ftp"
                    NetProtocol.SMB -> "smb"
                }
                // 443 for https, not 80 - otherwise an https address carries a ":443" that
                // says nothing and that somebody then has to check.
                val defaultPort =
                    if (protocol == NetProtocol.WEBDAV && tls) 443
                    else RemoteKinds.defaultPortFor(protocol)
                // The port is left off only when it adds nothing - an address carrying its own
                // default reads as noise somebody has to check.
                val portPart = if (port <= 0 || port == defaultPort) "" else ":" + port
                scheme + "://" + host + portPart + "/" + path +
                    (if (trailingSlash && path.isNotEmpty()) "/" else "")
            }
        }
    }

    /** The address for an existing connection. */
    fun compose(c: NetConnection): String =
        compose(c.protocol, c.host, c.port, c.share, c.useTls)

    /**
     * The kind an address most likely names, so the form can label itself before anything is typed.
     *
     * A guess that only chooses wording, never what is sent.
     */
    fun kindFor(parsed: ParsedAddress): RemoteKind = when (parsed.protocol) {
        NetProtocol.SMB, null -> RemoteKind.WINDOWS_PC
        NetProtocol.SFTP -> RemoteKind.MAC_OR_LINUX
        NetProtocol.FTP -> RemoteKind.OTHER
        NetProtocol.WEBDAV -> when (parsed.port) {
            8322 -> RemoteKind.FILET_DESKTOP
            // Filet's own hosting default, and the port a phone most often lands on.
            8321 -> RemoteKind.FILET_PHONE
            else -> if (parsed.share.startsWith("a/")) RemoteKind.FILET_PHONE else RemoteKind.OTHER
        }
    }
}
