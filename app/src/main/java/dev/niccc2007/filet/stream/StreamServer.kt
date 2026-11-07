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
    private val parked = HashMap<String, Parked>()

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
        for (p in parked.values) {
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
        val reader = take(token, start) ?: ReadAhead(scope, { at -> vfs.openReadAt(path, at) }, start, size)
        // A reader the drive failed under is not kept: the next request would only fail on it again.
        var keep = true
        try {
            var left = end - start + 1
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
        }
    }

    /** The parked reader of [token], when it stands at [start] or a little before it. */
    @Synchronized
    private fun take(token: String, start: Long): ReadAhead? {
        val p = parked.remove(token) ?: return null
        p.expiry.cancel()
        val gap = start - p.reader.position
        if (gap < 0 || gap > REUSE_GAP_BYTES) {
            p.reader.close()
            return null
        }
        return p.reader.also { if (gap > 0) it.skipLater(gap) }
    }

    @Synchronized
    private fun park(token: String, reader: ReadAhead) {
        parked.remove(token)?.let {
            it.expiry.cancel()
            it.reader.close()
        }
        val expiry = scope.launch {
            delay(KEEP_MS)
            synchronized(this@StreamServer) {
                if (parked[token]?.reader === reader) parked.remove(token)
            }
            reader.close()
        }
        parked[token] = Parked(reader, expiry)
    }

    /** One line of the request head, without its CR LF; null when the connection ends first. */
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
        const val AHEAD_BYTES = 32 * 1024 * 1024

        /** How far past where a kept reader stands a new request may start and still take it. */
        const val REUSE_GAP_BYTES = 8L * 1024 * 1024

        /** How long the last reader of a link is kept for the request that follows a seek. */
        const val KEEP_MS = 5_000L

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
) {
    class Piece(val bytes: ByteArray, val from: Int, val length: Int)

    /** The drive failed under the reader, as opposed to the player hanging up. */
    class DriveFailed(cause: Throwable) : IOException("reading the drive failed", cause)

    private val pieces = Channel<ByteArray>(StreamServer.AHEAD_BYTES / StreamServer.PIECE_BYTES)
    @Volatile private var stream: InputStream? = null
    private var head: ByteArray? = null
    private var headAt = 0
    private var skip = 0L

    /** The next byte [next] hands out. */
    var position: Long = start
        private set

    private val job = scope.launch {
        try {
            open(start).use { input ->
                stream = input
                var at = start
                while (at < size && isActive) {
                    val buf = ByteArray(minOf(StreamServer.PIECE_BYTES.toLong(), size - at).toInt())
                    var got = 0
                    while (got < buf.size) {
                        val n = input.read(buf, got, buf.size - got)
                        if (n < 0) break
                        got += n
                    }
                    if (got == 0) break
                    pieces.send(if (got == buf.size) buf else buf.copyOf(got))
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

    fun close() {
        job.cancel()
        pieces.cancel()
        // A read blocked on the network does not see the cancel; closing its stream does.
        runCatching { stream?.close() }
    }
}
