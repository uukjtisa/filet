package dev.niccc2007.filet.webdav

/**
 * `Range: bytes=…`, which is what makes a large file resumable.
 *
 * Its own file with tests because every clause is off-by-one bait and the symptoms are all
 * silent: a range one byte short truncates a video, a range one byte long makes Explorer
 * report a corrupt file, and a suffix range read as an absolute one serves the wrong part of
 * the file without any error anywhere.
 */
object DavRange {

    /**
     * @param start first byte served, inclusive.
     * @param end last byte served, **inclusive** - which is the HTTP convention and the
     *   opposite of almost every other end index in this codebase.
     */
    data class Spec(val start: Long, val end: Long, val satisfiable: Boolean) {
        val length: Long get() = if (satisfiable) end - start + 1 else 0
    }

    /**
     * Parse one range against a resource of [total] bytes.
     *
     * Returns null when there is no range at all - which is a different answer from a range
     * that cannot be satisfied, because one means "send the whole file" and the other means
     * "send a 416".
     */
    fun parse(header: String?, total: Long): Spec? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("bytes=", ignoreCase = true)) return null
        val spec = h.substring(6).trim()
        // Only the first range of a multi-range request is honoured. Serving several means a
        // multipart/byteranges body, which no file manager asks for, and answering the first
        // is explicitly allowed.
        val first = spec.substringBefore(',').trim()
        if (first.isEmpty()) return null

        val dash = first.indexOf('-')
        if (dash < 0) return null
        val fromText = first.substring(0, dash).trim()
        val toText = first.substring(dash + 1).trim()

        // A file of unknown length cannot have a range checked against it at all.
        if (total < 0) return null

        if (fromText.isEmpty()) {
            // A suffix range: the LAST n bytes, not the bytes from n onward. Reading it the
            // other way serves the wrong part of the file and nothing reports an error.
            val n = toText.toLongOrNull() ?: return null
            if (n <= 0) return Spec(0, 0, satisfiable = false)
            if (total == 0L) return Spec(0, 0, satisfiable = false)
            val start = (total - n).coerceAtLeast(0)
            return Spec(start, total - 1, satisfiable = true)
        }

        val start = fromText.toLongOrNull() ?: return null
        if (start < 0) return null
        // A start at or past the end is unsatisfiable, not empty: the distinction is a 416
        // against a 206 of zero bytes, and a client given the latter waits forever.
        if (total == 0L || start >= total) return Spec(start, start, satisfiable = false)

        val end = if (toText.isEmpty()) total - 1 else toText.toLongOrNull() ?: return null
        if (end < start) return Spec(start, end, satisfiable = false)
        // An end past the last byte is clamped rather than refused, which is what the spec
        // requires and what every client relies on when it asks for a round number.
        return Spec(start, end.coerceAtMost(total - 1), satisfiable = true)
    }
}
