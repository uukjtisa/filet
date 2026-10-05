package dev.niccc2007.filet.webdav

/**
 * Who reads a request body, and how much of it.
 *
 * ## The bug this exists to stop happening again
 *
 * The connection loop used to read the body of **any** request with a `Content-Length` under
 * 256 KB into a String, because PROPFIND and LOCK need to parse XML. It did that for `PUT` too.
 * `put()` was then handed the same, already-drained stream and read nothing from it - after
 * `openWrite(append = false)` had already truncated the target.
 *
 * So a file under 256 KB was emptied and the save failed, while a file over 256 KB wrote
 * correctly, because that body was deliberately left in the stream. A size-dependent data loss
 * that presented as a permissions problem.
 *
 * The decision "given these headers, who reads this body" is now made once, here, where it can be
 * tested exhaustively - rather than inferred from a length comparison in the middle of a loop.
 *
 * ## The framing cases
 *
 * Three exist and only one was handled:
 *
 *  - **`Content-Length`** - the common case.
 *  - **`Transfer-Encoding: chunked`** - no length at all. The old loop resolved that to `0`, read
 *    nothing, and let `put` truncate the file to zero and answer `201 Created`.
 *  - **`Expect: 100-continue`** - the client sends no body until the server says to. Never
 *    answered, so the client waited out its own timeout.
 */
object DavBody {

    /** What should happen to the bytes after the headers. */
    sealed interface Plan {
        /** No body. */
        data object None : Plan

        /** The loop reads [length] bytes into a string, for a handler that parses XML. */
        data class Buffer(val length: Int) : Plan

        /**
         * The handler owns the stream. The loop must not touch it.
         *
         * [length] is null when the body is chunked and the size is not known in advance.
         */
        data class Stream(val length: Long?) : Plan

        /**
         * Nobody wants it, but it is in the stream and has to go before the next request line.
         *
         * [length] is null for a chunked body, which has to be walked frame by frame.
         */
        data class Drain(val length: Long?) : Plan
    }

    /** The only verb that streams its body straight to a file. */
    private val STREAMING = setOf("PUT")

    /** Verbs whose body is XML the handler parses as a string. */
    private val PARSED = setOf("PROPFIND", "PROPPATCH", "LOCK", "UNLOCK")

    fun isChunked(headers: Map<String, String>): Boolean =
        headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true

    /**
     * Whether the client is holding its body back until told to proceed.
     *
     * Case-insensitive on the value as well as the name: the header is `100-continue` by
     * specification but is not always sent in lower case.
     */
    fun expectsContinue(headers: Map<String, String>): Boolean =
        headers["expect"]?.lowercase()?.contains("100-continue") == true

    /** The declared length, or null when chunked or absent. */
    fun declaredLength(headers: Map<String, String>): Long? {
        if (isChunked(headers)) return null
        return headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }

    /**
     * What to do with the body of [method].
     *
     * [maxBuffer] caps what may be held in memory as a string. A parsed body larger than that is
     * drained rather than buffered: a PROPFIND request body is a few hundred bytes in practice,
     * and one that is megabytes long is not a request worth honouring.
     */
    fun plan(method: String, headers: Map<String, String>, maxBuffer: Int): Plan {
        val chunked = isChunked(headers)
        val length = declaredLength(headers)
        val upper = method.uppercase()

        // Streaming verbs take the stream whatever the framing - including chunked, where the
        // handler reads frames until the terminator. This is the case that used to truncate.
        if (upper in STREAMING) {
            if (!chunked && (length == null || length == 0L)) {
                // A PUT with no body at all is a legitimate way to create an empty file, and is
                // distinct from a PUT whose body we failed to read.
                return Plan.Stream(0L)
            }
            return Plan.Stream(if (chunked) null else length)
        }

        if (!chunked && (length == null || length == 0L)) return Plan.None

        if (upper in PARSED) {
            if (chunked) return Plan.Drain(null)
            return if (length!! <= maxBuffer) Plan.Buffer(length.toInt()) else Plan.Drain(length)
        }

        // A verb that wants no body but was sent one. It still has to leave the stream, or the
        // next request line is read out of the middle of it.
        return Plan.Drain(if (chunked) null else length)
    }

    /**
     * What to do with a body belonging to a request that has already been refused.
     *
     * A write refused for permissions still has a body on the wire unless the client was waiting
     * to be told to send it. Leaving it there desynchronises the connection, and the next request
     * line is read out of the file contents - so a 403 would corrupt the request after it.
     *
     * When the client is holding back behind `Expect: 100-continue` there is nothing to drain,
     * because the refusal is the answer it was waiting for. That is also why a refusal must be
     * sent BEFORE any `100 Continue`: it stops the bytes ever being sent.
     */
    fun drainAfterRefusal(headers: Map<String, String>): Plan {
        if (expectsContinue(headers)) return Plan.None
        val chunked = isChunked(headers)
        val length = declaredLength(headers)
        if (!chunked && (length == null || length == 0L)) return Plan.None
        return Plan.Drain(if (chunked) null else length)
    }
}
