package dev.niccc2007.filet.stream

import dev.niccc2007.filet.webdav.DavRange

/**
 * The HTTP the stream server speaks, as pure functions: the head of an answer and the link's
 * path. Just enough of HTTP/1.1 for a media player on this phone, so everything here can be
 * checked without a socket. The Range header is read by [DavRange], the parser the WebDAV
 * server already trusts with the same off-by-one traps.
 */
object StreamHttp {

    /** The status line and headers of an answer. */
    data class Head(val status: Int, val reason: String, val headers: List<Pair<String, String>>) {
        /** As written to the socket, ending in the blank line. */
        fun text(): String = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }
    }

    /**
     * The head for [range] - as [DavRange.parse] read it, null for none - of a file of [size]
     * bytes and type [mime]. Every answer closes its connection: a player opens a new one on
     * every seek anyway, and one request a connection keeps the server free of keep-alive
     * bookkeeping.
     */
    fun head(range: DavRange.Spec?, size: Long, mime: String): Head {
        val common = listOf("Content-Type" to mime, "Accept-Ranges" to "bytes", "Connection" to "close")
        return when {
            range == null -> Head(200, "OK", common + ("Content-Length" to "$size"))
            !range.satisfiable -> Head(
                416, "Range Not Satisfiable",
                listOf("Content-Range" to "bytes */$size", "Content-Length" to "0", "Accept-Ranges" to "bytes", "Connection" to "close"),
            )
            else -> Head(
                206, "Partial Content",
                common + listOf(
                    "Content-Range" to "bytes ${range.start}-${range.end}/$size",
                    "Content-Length" to "${range.length}",
                ),
            )
        }
    }

    /** A bare answer with no body: not found, wrong method. */
    fun bare(status: Int, reason: String): Head =
        Head(status, reason, listOf("Content-Length" to "0", "Connection" to "close"))

    /** The token in a request target `/s/<token>/<name>`, or null when it is not one. */
    fun tokenOf(target: String): String? {
        val path = target.substringBefore('?')
        if (!path.startsWith("/s/")) return null
        val token = path.substring(3).substringBefore('/')
        return token.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * A file name as a path segment. The server never reads it back - the token is the whole
     * address - but a player that guesses the type from the end of the link needs it there.
     */
    fun segment(name: String): String = buildString {
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (plain) append(c.toChar()) else append('%').append("%02X".format(c))
        }
    }
}
