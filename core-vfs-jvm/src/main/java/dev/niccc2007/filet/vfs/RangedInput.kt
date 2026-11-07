package dev.niccc2007.filet.vfs

import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.InputStream

/**
 * A file read from [start] to [size] a chunk at a time, through a ranged read - the fallback
 * behind [FileSystemProvider.openReadAt] for a backend that can read a range but has no cheaper
 * way to start mid-file.
 *
 * Blocking, as an [InputStream] has to be: each chunk is fetched with [runBlocking] on the
 * caller's thread, which is always an IO thread here (the stream server's reader). A chunk that
 * comes back empty ends the stream rather than looping on it, so a file that shrank under the
 * reader ends early instead of hanging.
 */
class RangedInput(
    start: Long,
    private val size: Long,
    private val chunk: Int = CHUNK,
    private val fetch: suspend (from: Long, until: Long) -> ByteArray,
) : InputStream() {
    private var pos = start
    private var buf = ByteArray(0)
    private var at = 0

    private fun fill(): Boolean {
        if (at < buf.size) return true
        if (pos >= size) return false
        val until = minOf(size, pos + chunk)
        buf = runBlocking { fetch(pos, until) }
        at = 0
        if (buf.isEmpty()) return false
        pos += buf.size
        return true
    }

    override fun read(): Int = if (!fill()) -1 else buf[at++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buf.size - at)
        System.arraycopy(buf, at, b, off, n)
        at += n
        return n
    }

    override fun available(): Int = buf.size - at

    companion object {
        /** One request per megabyte: few enough round trips, small enough to start quickly. */
        const val CHUNK = 1 shl 20

        /**
         * Exactly [n] bytes from [input], or fewer only where the file ends first - the shape of
         * `readRange` for a backend that reads ranges by starting a stream mid-file.
         */
        fun readExactly(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(out, got, n - got)
                if (r < 0) break
                got += r
            }
            return if (got == n) out else out.copyOf(got)
        }

        /**
         * Drop [n] bytes from [input]. `skip` may skip fewer than asked, or none at all on a
         * network stream, so it is retried and backed by reading.
         */
        fun skipFully(input: InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s > 0) {
                    left -= s
                    continue
                }
                if (input.read() < 0) throw EOFException("the file ended $left bytes before the offset")
                left--
            }
        }
    }
}
