package dev.niccc2007.filet.stream

import android.net.Uri
import dev.niccc2007.filet.handlers.mimeOf
import dev.niccc2007.filet.log.FiletLog
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.webdav.DavRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays a file on a network drive straight off the drive: a small HTTP server on this phone's
 * loopback address that a player is given a link to, instead of a copy.
 *
 * ## Why
 *
 * A player needs to seek, and a share is not a file it can seek in. So a file on a share used
 * to be copied to the phone before it played, and anything over a few hundred megabytes was
 * refused with "copy it somewhere local first". Every player already knows how to seek in an
 * HTTP link with Range requests, so the share is offered as one: each request is answered by
 * reading the drive at the asked-for offset ([Vfs.openReadAt]).
 *
 * ## What it is careful about
 *
 * - **Only this phone.** Bound to 127.0.0.1, never the network, and each link carries a token
 *   for one file ([StreamTokens]). This is not the LAN share server (`nearby/HttpServer`): that
 *   one has PINs, sessions and other phones to think about, and none of it applies here.
 * - **A network hiccup is not a stutter.** Every connection reads through a [ReadAhead] that
 *   stays up to [AHEAD_BYTES] in front of the player, so a slow second on the share is spent
 *   out of the buffer rather than on screen.
 * - **A seek is cheap.** Players drop the connection and open a new one on every seek, often
 *   at nearly the place the last one stopped. The last reader of each link is kept for
 *   [KEEP_MS], and a new request at or a little past where it stood takes it over.
 * - **Started when a link is made, stopped when idle.** No connection for [IDLE_MS] closes the
 *   socket. A link made again opens it on the same port where it can, so a link a player
 *   still holds keeps working.
 */
class StreamServer private constructor(private val vfs: Vfs) {

    private val tokens = StreamTokens()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val open = AtomicInteger(0)
    private var socket: ServerSocket? = null
    private var lastPort = 0
    private var idle: Job? = null
    private val parked = HashMap<String, ArrayDeque<Parked>>()
    private val cache = PieceCache(CACHE_BYTES)

    private class Parked(val reader: ReadAhead, val expiry: Job)

    /** A link a player can stream [node] from, for as long as the app runs. */
    fun linkFor(node: VNode): Uri {
        val token = tokens.mint(StreamTokens.Entry(node.path, node.size, mimeOf(node)))
        val port = ensureStarted()
        return Uri.parse("http://127.0.0.1:$port/s/$token/" + StreamHttp.segment(node.name))
    }

    @Synchronized
    private fun ensureStarted(): Int {
        socket?.takeIf { !it.isClosed }?.let {
            touch()
            return it.localPort
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        // The port it had, so links handed out before an idle stop still answer.
        fun bound(port: Int) = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, port), 16)
        }
        val s = runCatching { bound(lastPort) }.getOrElse { bound(0) }
        socket = s
        lastPort = s.localPort
        FiletLog.i("stream", "listening on 127.0.0.1:${s.localPort}")
        scope.launch {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch { serve(c) }
            }
        }
        touch()
        return s.localPort
    }

    @Synchronized
    private fun touch() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            stopIfIdle()
        }
    }

    @Synchronized
    private fun stopIfIdle() {
        if (open.get() > 0) {
            touch()
            return
        }
        socket?.let { runCatching { it.close() } }
        socket = null
        for (p in parked.values.flatten()) {
            p.expiry.cancel()
            p.reader.close()
        }
        parked.clear()
        FiletLog.i("stream", "idle, stopped")
    }

    private suspend fun serve(c: Socket) {
        open.incrementAndGet()
        try {
            c.use { sock ->
                sock.soTimeout = READ_TIMEOUT_MS
                val input = sock.getInputStream()
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                val request = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = request.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty()
                if (method != "GET" && method != "HEAD") return answer(out, StreamHttp.bare(405, "Method Not Allowed"))
                val token = StreamHttp.tokenOf(target) ?: return answer(out, StreamHttp.bare(404, "Not Found"))
                val entry = tokens.resolve(token) ?: return answer(out, StreamHttp.bare(404, "Not Found"))
                val size = entry.size.takeIf { it >= 0 } ?: runCatching { vfs.stat(entry.path)?.size }.getOrNull() ?: -1L
                if (size < 0) return answer(out, StreamHttp.bare(404, "Not Found"))
                val range = DavRange.parse(headers["range"], size)
                answer(out, StreamHttp.head(range, size, entry.mime))
                if (method == "HEAD" || range?.satisfiable == false || size == 0L) return
                val start = range?.start ?: 0L
                val end = range?.end ?: (size - 1)
                pump(token, entry.path, size, start, end, out)
            }
        } catch (e: ReadAhead.DriveFailed) {
            FiletLog.w("stream", "the drive stopped answering: ${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
        } catch (e: IOException) {
            // The player hung up, which it does on every seek. Not an error.
        } catch (e: Exception) {
            FiletLog.w("stream", "serve failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            open.decrementAndGet()
            touch()
        }
    }

    private fun answer(out: OutputStream, head: StreamHttp.Head) {
        out.write(head.text().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private suspend fun pump(token: String, path: VPath, size: Long, start: Long, end: Long, out: OutputStream) {
        val began = System.nanoTime()
        var at = start
        // Somewhere already watched - a jump back - is answered from memory, with no drive at all.
        while (at <= end) {
            val base = at - at % PIECE_BYTES
            val piece = cache.get(token, base) ?: break
            val n = minOf(piece.size - (at - base), end - at + 1).toInt()
            if (n <= 0) break
            out.write(piece, (at - base).toInt(), n)
            at += n
        }
        val remembered = at - start
        if (at > end) {
            out.flush()
            log(start, end, remembered, began, "memory")
            return
        }
        val kept = take(token, at)
        // A new reader starts on a piece boundary, so every piece it reads is one the memory can
        // file and find again; the bytes before the request are skipped, not sent.
        val reader = kept ?: run {
            val aligned = at - at % PIECE_BYTES
            ReadAhead(scope, { o -> vfs.openReadAt(path, o) }, aligned, size) { o, bytes -> cache.put(token, o, bytes) }
                .also { if (at > aligned) it.skipLater(at - aligned) }
        }
        // A reader the drive failed under is not kept: the next request would only fail on it again.
        var keep = true
        try {
            var left = end - at + 1
            while (left > 0) {
                val piece = reader.next(left) ?: break
                out.write(piece.bytes, piece.from, piece.length)
                left -= piece.length
            }
            out.flush()
        } catch (e: ReadAhead.DriveFailed) {
            keep = false
            throw e
        } finally {
            if (keep) park(token, reader) else reader.close()
            log(start, end, remembered, began, if (kept != null) "kept reader" else "new reader")
        }
    }

    /**
     * One line a request, for telling a slow drive from a player that keeps jumping: where it
     * asked, how much came from memory, how long it was open, and which reader served it.
     */
    private fun log(start: Long, end: Long, remembered: Long, began: Long, how: String) {
        val ms = (System.nanoTime() - began) / 1_000_000
        FiletLog.i("stream", "bytes $start-$end, ${remembered / 1024} KB from memory, $how, open $ms ms")
    }

    /**
     * The kept reader that reaches [start] with the least skipping, taken out of the keep and
     * woken - see [ReadAhead.hold].
     */
    @Synchronized
    private fun take(token: String, start: Long): ReadAhead? {
        val list = parked[token] ?: return null
        val i = nearestBehind(list.map { it.reader.position }, start, REUSE_GAP_BYTES) ?: return null
        val p = list.removeAt(i)
        if (list.isEmpty()) parked.remove(token)
        p.expiry.cancel()
        val gap = start - p.reader.position
        if (gap > 0) p.reader.skipLater(gap)
        p.reader.hold = false
        return p.reader
    }

    /**
     * Keep [reader] for the request that follows a seek. Several a link, because a player that
     * reads two places in turn - the picture in one part of a file and its sound in another -
     * otherwise threw away the other reader on every switch and started from nothing. A kept
     * reader holds what it has and reads no more: abandoned readers fetching ahead are exactly
     * what starves the one being watched when somebody jumps about.
     */
    @Synchronized
    private fun park(token: String, reader: ReadAhead) {
        reader.hold = true
        val list = parked.getOrPut(token) { ArrayDeque() }
        val expiry = scope.launch {
            delay(KEEP_MS)
            synchronized(this@StreamServer) {
                parked[token]?.let { l ->
                    l.removeAll { it.reader === reader }
                    if (l.isEmpty()) parked.remove(token)
                }
            }
            reader.close()
        }
        list.addLast(Parked(reader, expiry))
        while (list.size > PARKED_PER_FILE) list.removeFirst().let { it.expiry.cancel(); it.reader.close() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8 * 1024) return null
            sb.append(b.toChar())
        }
    }

    companion object {
        /** What the drive is read in ahead of the player: one piece at a time. */
        const val PIECE_BYTES = 256 * 1024

        /** How far ahead of the player a connection reads. */
        const val AHEAD_BYTES = 24 * 1024 * 1024

        /** How far past where a kept reader stands a new request may start and still take it. */
        const val REUSE_GAP_BYTES = 8L * 1024 * 1024

        /** How long the last reader of a link is kept for the request that follows a seek. */
        const val KEEP_MS = 5_000L

        /** Kept readers a link may have at once. See [park]. */
        const val PARKED_PER_FILE = 3

        /**
         * Pieces already sent, kept for a jump back. Bounded for a phone with little memory:
         * with the read-ahead of the reader in use and the kept ones, the server's worst case
         * stays near a hundred megabytes, and is usually far below it.
         */
        const val CACHE_BYTES = 24L * 1024 * 1024

        /**
         * The index in [positions] of the reader that reaches [start] with the least skipping,
         * or null where none stands at or behind it within [maxGap].
         */
        fun nearestBehind(positions: List<Long>, start: Long, maxGap: Long): Int? =
            positions.withIndex().filter { start - it.value in 0..maxGap }.minByOrNull { start - it.value }?.index

        /**
         * No connection for this long stops the server. Long, because a paused player holds a
         * link without a connection, and the port coming back is what keeps that link working.
         */
        const val IDLE_MS = 20L * 60 * 1000

        private const val READ_TIMEOUT_MS = 30_000

        @Volatile private var instance: StreamServer? = null

        /** The one server, over [vfs]. */
        fun of(vfs: Vfs): StreamServer =
            instance ?: synchronized(this) { instance ?: StreamServer(vfs).also { instance = it } }
    }
}

/**
 * Reads a file from [start] to its end, up to [StreamServer.AHEAD_BYTES] in front of whoever
 * takes the bytes, so the network's pauses are absorbed here and not in the player.
 */
internal class ReadAhead(
    scope: CoroutineScope,
    open: suspend (Long) -> InputStream,
    start: Long,
    private val size: Long,
    private val onPiece: (at: Long, bytes: ByteArray) -> Unit = { _, _ -> },
) {
    class Piece(val bytes: ByteArray, val from: Int, val length: Int)

    /** The drive failed under the reader, as opposed to the player hanging up. */
    class DriveFailed(cause: Throwable) : IOException("reading the drive failed", cause)

    private val pieces = Channel<ByteArray>(StreamServer.AHEAD_BYTES / StreamServer.PIECE_BYTES)
    @Volatile private var stream: InputStream? = null
    private var head: ByteArray? = null
    private var headAt = 0
    private var skip = 0L

    /**
     * While true the reader keeps what it has read and reads no further - set while it is kept
     * between requests, so only the reader somebody is watching uses the network.
     */
    @Volatile var hold = false

    /** The next byte [next] hands out. */
    var position: Long = start
        private set

    private val job = scope.launch {
        try {
            open(start).use { input ->
                stream = input
                var at = start
                while (at < size && isActive) {
                    while (hold && isActive) delay(HOLD_POLL_MS)
                    val t0 = System.nanoTime()
                    val buf = ByteArray(minOf(StreamServer.PIECE_BYTES.toLong(), size - at).toInt())
                    var got = 0
                    while (got < buf.size) {
                        val n = input.read(buf, got, buf.size - got)
                        if (n < 0) break
                        got += n
                    }
                    if (got == 0) break
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    if (ms > SLOW_PIECE_MS) FiletLog.w("stream", "the drive took $ms ms for ${got / 1024} KB at $at")
                    val piece = if (got == buf.size) buf else buf.copyOf(got)
                    onPiece(at, piece)
                    pieces.send(piece)
                    at += got
                }
            }
            pieces.close()
        } catch (e: Throwable) {
            pieces.close(e)
        }
    }

    /** Drop the next [n] bytes before handing any out: a request a little past where this stood. */
    fun skipLater(n: Long) {
        skip += n
    }

    /** Up to [max] bytes from [position], waiting for the drive; null at the end. */
    suspend fun next(max: Long): Piece? {
        while (true) {
            var h = head
            if (h == null || headAt >= h.size) {
                val r = pieces.receiveCatching()
                r.exceptionOrNull()?.let { throw DriveFailed(it) }
                h = r.getOrNull() ?: return null
                head = h
                headAt = 0
            }
            if (skip > 0) {
                val n = minOf(skip, (h.size - headAt).toLong()).toInt()
                headAt += n
                skip -= n
                position += n
                continue
            }
            val n = minOf(max, (h.size - headAt).toLong()).toInt()
            val piece = Piece(h, headAt, n)
            headAt += n
            position += n
            return piece
        }
    }

    private companion object {
        const val HOLD_POLL_MS = 40L

        /** A piece slower than this is logged: a drive that cannot keep up with the video. */
        const val SLOW_PIECE_MS = 1_500L
    }

    fun close() {
        job.cancel()
        pieces.cancel()
        // A read blocked on the network does not see the cancel; closing its stream does.
        runCatching { stream?.close() }
    }
}

/**
 * Pieces of streamed files already sent, the most recently used kept, up to [maxBytes]. Keyed by
 * link and the piece's offset, which is always a multiple of [StreamServer.PIECE_BYTES].
 */
internal class PieceCache(private val maxBytes: Long) {
    private val map = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(token: String, at: Long): ByteArray? = map["$token@$at"]

    @Synchronized
    fun put(token: String, at: Long, piece: ByteArray) {
        if (piece.size > maxBytes) return
        val key = "$token@$at"
        map.put(key, piece)?.let { bytes -= it.size }
        bytes += piece.size
        val i = map.entries.iterator()
        while (bytes > maxBytes && i.hasNext()) {
            val e = i.next()
            if (e.key == key) continue
            bytes -= e.value.size
            i.remove()
        }
    }

    @Synchronized
    fun size(): Long = bytes
}
