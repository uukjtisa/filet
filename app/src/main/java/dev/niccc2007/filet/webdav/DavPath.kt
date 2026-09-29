package dev.niccc2007.filet.webdav

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Turning a WebDAV request line into somewhere on this device, and back.
 *
 * This is the whole attack surface of hosting. Every other file Filet serves over the network
 * is reached by an opaque token that resolves through a set the user built by hand, so a path
 * is not expressible in the URL at all. WebDAV cannot work that way: Windows Explorer walks a
 * tree, and the tree has to have real names in it.
 *
 * So the rules are here, alone, in one file with tests, and the server is not allowed to build
 * a path any other way:
 *
 *  - **The root is a prefix, and everything is under it.** A resolved path that is not inside
 *    the share is refused rather than clamped, because clamping turns a traversal attempt into
 *    a silent success on the wrong file.
 *  - **Decode once.** `%2e%2e` decodes to `..`; decoding twice turns `%252e%252e` into it too.
 *    Segments are checked after exactly one decode and a second decode never happens.
 *  - **`.` and `..` never survive**, in any encoding, in any position.
 *  - **A backslash is a separator on the client and a legal filename character here.** Windows
 *    sends them; treating them as ordinary text lets `..\..\x` through a check that only looks
 *    for forward slashes.
 *  - **The access code is part of the path**, because there is no sign-in box. It is compared
 *    in constant time so the URL space cannot be probed a character at a time.
 */
object DavPath {

    /** What a request resolved to, or why it did not. */
    sealed interface Resolved {
        /**
         * Inside the share. [rel] is the path relative to the share root, without a leading slash.
         *
         * [code] is the code that matched, which is also which share was asked for - see
         * [DavShares]. It defaults to empty so a single-share resolve reads exactly as it did.
         */
        data class Ok(val rel: String, val code: String = "") : Resolved

        /** The code was missing or wrong. */
        data object Forbidden : Resolved

        /** Syntactically hostile - traversal, an unmapped prefix, or a bad escape. */
        data object Refused : Resolved
    }

    /**
     * Resolve a raw request target against the share mounted at `/a/<code>`.
     *
     * @param target the request line's path, still percent-encoded, query included.
     * @param code the access code this share was started with.
     */
    fun resolve(target: String, code: String): Resolved = resolve(target, listOf(code))

    /**
     * Resolve against every code currently listening, and report which one answered.
     *
     * The codes are tried in order and each is compared in constant time. Comparing against
     * several does leak how many shares are up, through timing, to someone who can measure a
     * network round trip to a phone on their own Wi-Fi - which is not a secret and not worth
     * making the code harder to read for.
     *
     * An empty list refuses everything. That is the honest answer for a socket with nothing
     * behind it, and it is what the accept loop wants during a teardown.
     */
    fun resolve(target: String, codes: List<String>): Resolved {
        val path = target.substringBefore('?').substringBefore('#')

        // Windows asks for the literal string /DavWWWRoot when it is working out whether a
        // host speaks WebDAV. It is a marker, not a folder, and it appears before the mount.
        var p = path.removePrefix("/DavWWWRoot")
        if (!p.startsWith("/a/")) return Resolved.Refused
        p = p.removePrefix("/a/")

        val slash = p.indexOf('/')
        val given = if (slash < 0) p else p.substring(0, slash)
        val rest = if (slash < 0) "" else p.substring(slash + 1)

        val decodedCode = decodeOnce(given) ?: return Resolved.Refused
        val matched = codes.firstOrNull { constantTimeEquals(decodedCode, it) }
            ?: return Resolved.Forbidden

        val segments = ArrayList<String>()
        for (raw in rest.split('/')) {
            if (raw.isEmpty()) continue
            val seg = decodeOnce(raw) ?: return Resolved.Refused
            // After exactly one decode, and before anything else looks at it.
            if (seg == "." || seg == "..") return Resolved.Refused
            // A separator that survived decoding is one that was hidden inside an escape.
            if (seg.contains('/') || seg.contains('\\')) return Resolved.Refused
            if (seg.contains('\u0000')) return Resolved.Refused
            segments.add(seg)
        }
        return Resolved.Ok(segments.joinToString("/"), matched)
    }

    /**
     * The href to put in a PROPFIND response for [rel].
     *
     * Re-encoded rather than echoed back: the client sent one encoding of the name and the
     * response has to carry an encoding of the name on disk, which after a MOVE or a rename is
     * not the same string.
     */
    fun href(code: String, rel: String, isDir: Boolean): String {
        val body = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
        val base = "/a/" + encode(code) + if (body.isEmpty()) "" else "/$body"
        // A collection's href ends in a slash. Windows uses this to decide whether it is
        // looking at a folder before it has read the resourcetype, and gets it visibly wrong
        // when it is missing - the folder opens as a zero-byte file.
        return if (isDir && !base.endsWith("/")) "$base/" else base
    }

    /** The last segment of [rel], or an empty string at the root. */
    fun nameOf(rel: String): String = rel.substringAfterLast('/', rel)

    /** The parent of [rel], or null at the root. */
    fun parentOf(rel: String): String? {
        if (rel.isEmpty()) return null
        val i = rel.lastIndexOf('/')
        return if (i < 0) "" else rel.substring(0, i)
    }

    /**
     * Percent-decode exactly once, or null if the escaping is malformed.
     *
     * `URLDecoder` also turns `+` into a space, which is correct for a form body and wrong for
     * a path - a file legitimately named `a+b` would be served as `a b` and PUT back under the
     * wrong name. So `+` is protected before decoding and restored after.
     */
    fun decodeOnce(s: String): String? = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Percent-encode one path segment for an href. */
    fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/")

    /**
     * Compare without leaking where the mismatch was.
     *
     * The code is the only thing standing between this share and the network, and it sits in a
     * URL that anything on the network can try. An early-exit comparison answers a probe in a
     * measurably different time per matching character.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        var diff = x.size xor y.size
        for (i in x.indices) diff = diff or (x[i].toInt() xor y.getOrElse(i) { 0 }.toInt())
        return diff == 0
    }
}
