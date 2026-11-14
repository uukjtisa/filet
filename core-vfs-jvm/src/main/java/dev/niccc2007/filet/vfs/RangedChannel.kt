package dev.niccc2007.filet.vfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * A file on another device, read in blocks through ranged reads and kept - the bytes behind a
 * [RangedChannel], for a reader that jumps around in a file rather than reading it front to back.
 *
 * A zip is the reason. Its table of contents is at the END of the file, and a zip reader looks
 * for it with a scan of small reads backwards from the end, walks it in reads of forty-odd bytes,
 * and then jumps to each member it opens. Over a network each of those as its own request is
 * hundreds of round trips; cut into blocks and kept, they are a handful.
 *
 * ## Runs
 *
 * A miss right after the previous miss fetches more than one block - two, then four, up to
 * [maxRun] - because a reader walking forward through a table of contents or a member wants the
 * next blocks too, and one request for a megabyte costs far less over a network than sixteen of
 * 64 KB. A miss anywhere else starts again at one block, so a jump to a small member costs one
 * block and not a megabyte.
 *
 * ## Bounds
 *
 * At most [keep] blocks are held, the least recently used dropped first. [keep] is at least
 * [maxRun], so a run never pushes out the block it was fetched for.
 *
 * Thread-safe: every read takes this object's lock. Several channels share one instance - a
 * zip reader opens one per member - so what one reader fetched the next one finds.
 *
 * @param length the file's size, known before the first read: the reader asks for it first.
 * @param fetch bytes `[from, until)`, which must be exactly that many. Blocking; it runs on the
 *   reader's own thread, which is always an IO thread here.
 */
class RangedBlocks(
    val length: Long,
    val block: Int = BLOCK,
    val keep: Int = KEEP,
    val maxRun: Int = MAX_RUN,
    private val fetch: (from: Long, until: Long) -> ByteArray,
) {
    init {
        require(length >= 0) { "bad length $length" }
        require(block > 0) { "bad block $block" }
        require(maxRun >= 1 && keep >= maxRun) { "keep $keep must hold a run of $maxRun" }
    }

    private val held = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            this.size > keep
    }

    /** The last block of the previous fetch, to tell a reader walking forward from one jumping. */
    private var lastFetched = -2L
    private var run = 1

    /** How many ranged reads were made. What the tests count, and what the log reports. */
    var requests: Int = 0
        private set

    /** How many bytes those reads brought over. */
    var fetched: Long = 0L
        private set

    /**
     * Copy up to [len] bytes at [pos] into [dst] from [off]. Returns how many, or -1 at or past
     * the end. Never fewer than asked except at the end of the file.
     */
    @Synchronized
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        require(pos >= 0) { "bad position $pos" }
        if (pos >= length) return -1
        var done = 0
        var at = pos
        while (done < len && at < length) {
            val index = at / block
            val data = blockAt(index)
            val inside = (at - index * block).toInt()
            val n = minOf(len - done, data.size - inside)
            System.arraycopy(data, inside, dst, off + done, n)
            done += n
            at += n
        }
        return done
    }

    private fun blockAt(index: Long): ByteArray {
        held[index]?.let { return it }
        run = if (index == lastFetched + 1) minOf(run * 2, maxRun) else 1
        val last = (length - 1) / block
        var count = minOf(run.toLong(), last - index + 1).toInt()
        // Nothing already held is fetched again at the end of a run.
        while (count > 1 && held.containsKey(index + count - 1)) count--
        val from = index * block
        val until = minOf(length, (index + count) * block)
        val bytes = fetch(from, until)
        requests++
        fetched += bytes.size
        // A short answer is a file that changed under the reader, or a server that sent less
        // than it was asked for. Either way the bytes cannot be placed, and guessing would
        // hand a zip reader the wrong part of the file as if it were the right one.
        if (bytes.size.toLong() != until - from) {
            throw IOException("asked for ${until - from} bytes at $from and got ${bytes.size}")
        }
        var first: ByteArray? = null
        for (i in 0 until count) {
            val s = i * block
            val piece = bytes.copyOfRange(s, minOf(bytes.size, s + block))
            if (i == 0) first = piece
            held[index + i] = piece
        }
        lastFetched = index + count - 1
        return first!!
    }

    companion object {
        /** 64 KB: one block takes in the end of any zip, whose last record is under 64 KB + 22. */
        const val BLOCK = 64 * 1024

        /** A megabyte at most per request, the same step [RangedInput] streams in. */
        const val MAX_RUN = 16

        /** Two megabytes held per file: a table of contents of tens of thousands of names. */
        const val KEEP = 32
    }
}

/**
 * A read-only, seekable view of [blocks], for a reader that takes a channel rather than a file.
 *
 * Closing a channel does not drop the blocks: they belong to whoever made them, and the next
 * reader on the same file should find what this one fetched.
 */
class RangedChannel(private val blocks: RangedBlocks) : SeekableByteChannel {
    private var pos = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        val want = dst.remaining()
        if (want == 0) return 0
        val n = try {
            if (dst.hasArray()) {
                blocks.read(pos, dst.array(), dst.arrayOffset() + dst.position(), want)
                    .also { if (it > 0) dst.position(dst.position() + it) }
            } else {
                val tmp = ByteArray(want)
                blocks.read(pos, tmp, 0, want).also { if (it > 0) dst.put(tmp, 0, it) }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // A failed ranged read arrives as whatever the backend throws; a channel speaks
            // IOException, and a reader above it catches only that.
            throw IOException(e.message ?: "could not read from the share", e)
        }
        if (n > 0) pos += n
        return n
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun position(): Long = pos

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0) { "bad position $newPosition" }
        pos = newPosition
        return this
    }

    override fun size(): Long = blocks.length

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}
