package dev.niccc2007.filet.desktop.mount

import dev.niccc2007.filet.webdav.AccessCode

/**
 * Where one share answers: host, port, scheme, and the access code that selects it.
 *
 * The code is the one part of an endpoint that is never broadcast - the announcement carries
 * `path=/a/` and the code is typed in - so a [DavTarget] only exists once somebody has supplied
 * it. Everything a platform needs to attach a drive is a pure function of this, and lives here so
 * it can be tested on a machine that cannot mount anything.
 */
data class DavTarget(
    val host: String,
    val port: Int,
    val secure: Boolean,
    /** The base path from the announcement, always `/a/` today. */
    val base: String,
    val code: String,
) {
    /** `http://host:port/a/code/`, the form macOS, `davfs2` and `gio` all take. */
    val url: String
        get() = (if (secure) "https" else "http") + "://" + urlHost(host) + ":" + port +
            normBase(base) + code + "/"

    /** The same endpoint as `gio` names it, `dav://` or `davs://`. */
    val gioUrl: String
        get() = (if (secure) "davs" else "dav") + "://" + urlHost(host) + ":" + port + normBase(base) + code + "/"

    /**
     * The UNC path Windows' WebDAV redirector takes: `\\host@port\DavWWWRoot\a\code`.
     *
     * `DavWWWRoot` is the redirector's marker for "the root of this server" and the server strips
     * it (`DavPath.resolve`). `@SSL` goes before the port for https - after it, the redirector
     * reads `SSL` as part of the port and answers with a bad-path error. An IPv6 literal cannot
     * appear in a UNC path at all; Windows' answer is the `ipv6-literal.net` spelling, where
     * colons become dashes and a zone index's `%` becomes `s`.
     */
    val unc: String
        get() {
            val h = if (host.contains(':')) {
                host.trim('[', ']').replace(':', '-').replace('%', 's') + ".ipv6-literal.net"
            } else host
            val segs = normBase(base).trim('/').split('/').filter { it.isNotEmpty() }
            return "\\\\" + h + (if (secure) "@SSL" else "") + "@" + port + "\\DavWWWRoot\\" +
                (segs + code).joinToString("\\")
        }

    /**
     * The same share as [other]: host as DNS compares it, port, scheme and code. Not `==`, which
     * also compares the base and the host's case - and Windows hands a UNC back with the host
     * spelled however it pleases.
     */
    fun sameShare(other: DavTarget): Boolean =
        host.equals(other.host, ignoreCase = true) && port == other.port && secure == other.secure && code == other.code

    companion object {
        private fun normBase(b: String): String {
            val t = b.trim().ifEmpty { "/a/" }
            return (if (t.startsWith("/")) t else "/$t").let { if (it.endsWith("/")) it else "$it/" }
        }

        private fun urlHost(h: String) = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

        /**
         * The code as typed, or null when it cannot be one.
         *
         * [AccessCode]'s rule, the one the phone's server checks. Spaces are dropped because a
         * code read across a room gets typed as `ab 12`. Case is kept: the server compares
         * exactly, and lowercasing here would turn a correct pinned `Ab12` into a refusal that
         * looks like a wrong code.
         */
        fun cleanCode(typed: String): String? = AccessCode.clean(typed)

        /**
         * Read an endpoint back out of a UNC path Windows reports for a mapped drive, or null
         * when the mapping is not a Filet share.
         *
         * This is how a drive mapped in an earlier run - Windows remembers it across logins -
         * shows up in the list again, and how a drive disconnected from Explorer drops out of
         * it: the list is whatever Windows says is mapped, not what this process remembers.
         *
         * Bug identified here: every mapped phone drive read as not mounted. Windows reports a
         * WebDAV drive in two spellings. The remembered entry under `HKCU\Network` keeps the
         * `DavWWWRoot` marker, `\\192.168.5.23@8321\DavWWWRoot\a\tres33`, but the redirector's
         * own name for a connected drive drops it, `\\192.168.5.23@8321\a\tres33` - and that is
         * the one `WNetEnumResource` returns, and what `net use` prints. Only the first was
         * accepted, so every connected drive was invisible. Both are read now. The short form
         * needs the `@port` (or `@SSL`) a redirector name always carries for a phone, so an SMB
         * mapping of a folder that happens to be called `a` is not mistaken for one.
         */
        fun parseUnc(unc: String): DavTarget? {
            if (!unc.startsWith("\\\\")) return null
            val all = unc.substring(2).split('\\')
            val marked = all.getOrNull(1).equals("DavWWWRoot", ignoreCase = true)
            if (!marked && '@' !in all[0]) return null
            val parts = if (marked) all.take(1) + all.drop(2) else all
            if (parts.size < 3 || !parts[1].equals("a", ignoreCase = true)) return null
            val code = parts[2].takeIf { it.isNotEmpty() } ?: return null
            val hostPart = parts[0].split('@')
            val host0 = hostPart[0]
            val secure = hostPart.drop(1).any { it.equals("SSL", ignoreCase = true) }
            val port = hostPart.drop(1).firstNotNullOfOrNull { it.toIntOrNull() } ?: if (secure) 443 else 80
            val host = if (host0.endsWith(".ipv6-literal.net", ignoreCase = true)) {
                host0.dropLast(".ipv6-literal.net".length).replace('-', ':').replace('s', '%')
            } else host0
            return DavTarget(host, port, secure, "/a/", code)
        }
    }
}
