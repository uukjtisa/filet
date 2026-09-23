package dev.niccc2007.filet.webdav

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/** One computer that has this phone mounted. */
data class DavClient(
    val address: String,
    val agent: String,
    val since: Long,
    var lastSeen: Long,
    var reads: Int = 0,
    var writes: Int = 0,
)

/** What the hosting card draws itself from. */
data class DavState(
    val running: Boolean = false,
    val port: Int = 0,
    val code: String = "",
    val writable: Boolean = false,
    val scope: DavScope = DavScope.SHARED_FOLDER,
    val idleStopMinutes: Int = 30,
    val clients: List<DavClient> = emptyList(),
    val lastActivity: Long = 0,
)

/** How much of the phone the computer can see. */
enum class DavScope(val label: String) {
    /** `Filet/Shared` only. The default, and the one worth defaulting to. */
    SHARED_FOLDER("Filet/Shared"),

    /** Everything Filet itself can reach. */
    WHOLE_PHONE("Whole phone"),
}

/**
 * Filet as a WebDAV server, so the phone appears in Windows Explorer.
 *
 * WebDAV rather than SMB, and that is not a preference. SMB listens on port 445, ports below
 * 1024 are privileged on Android, and nothing without root may bind one - so an SMB server in
 * this app is not a thing that can be written, at any level of effort. WebDAV is HTTP plus
 * about six verbs, runs on any port, and Explorer, Finder and every Linux file manager speak
 * it natively. Nothing is bought, nothing is hosted for anyone, and no account exists: the
 * phone is the server and the network is the only thing in between.
 *
 * Security, in the order it is enforced:
 *
 *  1. Nothing listens until the user presses the button, and it stops itself when idle.
 *  2. **The access code is in the path**, because Explorer's credential prompt is a poor place
 *     to put a per-session secret and refusing without one makes the drive un-mappable. Every
 *     request re-checks it in constant time; there is no cookie and no session to steal.
 *  3. **Read-only by default.** PUT, DELETE, MKCOL, MOVE and COPY answer 403 until writing is
 *     turned on for that session, and the switch does not persist.
 *  4. **Every path goes through [DavPath]**, which has tests, and nothing else builds one.
 *
 * Written on `ServerSocket` for the same reason the Nearby server is: the one component in
 * this app that listens should be small enough to read in an afternoon.
 */
class WebDavServer(
    private val vfs: Vfs,
    /** Where the share is rooted, resolved fresh per session from the chosen scope. */
    private val rootFor: (DavScope) -> VPath?,
    private val onEvent: (DavState) -> Unit,
) {
    private val pool = Executors.newFixedThreadPool(4)
    private var socket: ServerSocket? = null
    private val generation = AtomicLong(0)
    @Volatile private var running = false
    @Volatile private var root: VPath? = null

    private val clients = ConcurrentHashMap<String, DavClient>()
    private val locks = DavLocks()
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val random = SecureRandom()

    @Volatile var code: String = ""
        private set
    @Volatile var writable: Boolean = false
    @Volatile var scope: DavScope = DavScope.SHARED_FOLDER
    @Volatile var idleStopMinutes: Int = 30
    var port: Int = 0
        private set

    fun state(): DavState = DavState(
        running = running,
        port = port,
        code = code,
        writable = writable,
        scope = scope,
        idleStopMinutes = idleStopMinutes,
        clients = clients.values.sortedBy { it.since },
        lastActivity = lastActivity.get(),
    )

    fun start(preferredPort: Int = DEFAULT_PORT): DavState {
        if (running) return state()
        val base = rootFor(scope) ?: return state()
        root = base
        // Short and unambiguous: it is typed into an address bar by hand, sometimes read off a
        // screen across a room. No vowels, so it cannot spell anything, and no characters that
        // look like each other in the fonts Explorer uses.
        code = (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        clients.clear()
        locks.clear()
        lastActivity.set(System.currentTimeMillis())

        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort), 16)
            }
        } catch (e: Exception) {
            // Any free port rather than failing: the preferred one being taken is common and
            // recoverable, and the address is copied from the card rather than memorised.
            try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0), 16) }
            } catch (e2: Exception) {
                return state()
            }
        }
        socket = s
        port = s.localPort
        running = true
        val mine = generation.incrementAndGet()
        pool.execute { accept(s, mine) }
        onEvent(state())
        return state()
    }

    fun stop(): DavState {
        running = false
        generation.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        clients.clear()
        locks.clear()
        root = null
        onEvent(state())
        return state()
    }

    /** Close the session if nothing has touched it for the configured idle window. */
    fun stopIfIdle(now: Long = System.currentTimeMillis()) {
        if (!running || idleStopMinutes <= 0) return
        if (now - lastActivity.get() >= idleStopMinutes * 60_000L) stop()
    }

    fun kick(address: String) {
        clients.remove(address)
        onEvent(state())
    }

    private fun accept(server: ServerSocket, mine: Long) {
        while (running && generation.get() == mine && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                // A closed socket during shutdown is the expected way out, not a fault.
                if (!running || generation.get() != mine) return
                continue
            }
            pool.execute { runCatching { serve(client) }; runCatching { client.close() } }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = PushbackInputStream(socket.getInputStream().buffered(), 8)
        val out = BufferedOutputStream(socket.getOutputStream())
        val address = socket.inetAddress?.hostAddress.orEmpty()

        // One connection carries many requests: Explorer opens a socket and walks the tree
        // down it. Closing after one turns a folder listing into a hundred handshakes.
        while (running) {
            val line = readLine(input) ?: return
            if (line.isBlank()) return
            val parts = line.split(' ')
            if (parts.size < 3) { status(out, 400, "Bad Request"); out.flush(); return }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = readHeaders(input) ?: return
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            val body = if (length in 1..MAX_BODY_READ) readExactly(input, length.toInt()) else ""
            // A body too large to hold is drained rather than left in the stream, or the next
            // request line is read out of the middle of it.
            if (length > MAX_BODY_READ && method != "PUT") drain(input, length)

            lastActivity.set(System.currentTimeMillis())
            touch(address, headers["user-agent"].orEmpty())

            handle(method, target, headers, body, input, out, address, length)
            out.flush()
            if (headers["connection"]?.lowercase() == "close") return
        }
    }

    private fun handle(
        method: String,
        target: String,
        headers: Map<String, String>,
        body: String,
        input: InputStream,
        out: OutputStream,
        address: String,
        contentLength: Long,
    ) {
        // OPTIONS is answered before the path is resolved. Explorer sends it at the root of
        // the server to find out whether it speaks WebDAV at all, and a 403 there ends the
        // conversation before the mount is ever attempted.
        if (method == "OPTIONS") return options(out)

        when (val r = DavPath.resolve(target, code)) {
            is DavPath.Resolved.Forbidden -> return status(out, 403, "Forbidden")
            is DavPath.Resolved.Refused -> return status(out, 400, "Bad Request")
            is DavPath.Resolved.Ok -> {
                val base = root ?: return status(out, 503, "Service Unavailable")
                val path = childOf(base, r.rel)
                val needsWrite = method in WRITE_METHODS
                if (needsWrite && !writable) return status(out, 403, "Forbidden")
                when (method) {
                    "PROPFIND" -> propfind(out, path, r.rel, headers, body)
                    "HEAD" -> get(out, path, r.rel, headers, headOnly = true)
                    "GET" -> get(out, path, r.rel, headers, headOnly = false)
                    "PUT" -> put(out, path, input, contentLength, address)
                    "DELETE" -> delete(out, path)
                    "MKCOL" -> mkcol(out, path)
                    "MOVE" -> moveOrCopy(out, path, headers, move = true)
                    "COPY" -> moveOrCopy(out, path, headers, move = false)
                    "LOCK" -> lock(out, r.rel, headers, body)
                    "UNLOCK" -> unlock(out, r.rel, headers)
                    // Explorer PROPPATCHes timestamps on every file it writes. Refusing makes
                    // a copy into the drive report failure after the bytes already landed, so
                    // the honest answer is that the request was understood and the property is
                    // not stored - which is what a 207 of 403s says.
                    "PROPPATCH" -> proppatch(out, r.rel)
                    else -> status(out, 405, "Method Not Allowed")
                }
            }
        }
    }

    // ── verbs ───────────────────────────────────────────────────────────────────────────

    private fun options(out: OutputStream) {
        status(
            out, 200, "OK",
            listOf(
                // Class 2 and not 1. Explorer will mount a class-1 share read-only whatever
                // else it is told, because it assumes it cannot lock and refuses to write.
                "DAV: 1, 2",
                "MS-Author-Via: DAV",
                "Allow: OPTIONS, GET, HEAD, PROPFIND, PROPPATCH, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK",
                "Content-Length: 0",
            ),
        )
    }

    private fun propfind(
        out: OutputStream,
        path: VPath,
        rel: String,
        headers: Map<String, String>,
        body: String,
    ) {
        val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull()
            ?: return status(out, 404, "Not Found")
        // Depth "infinity" is answered as 1. A recursive PROPFIND over a phone's whole storage
        // is a denial of service that the protocol invites politely, and every client that
        // matters copes with the shallower answer by walking.
        val depth = headers["depth"] ?: "1"
        val entries = ArrayList<DavXml.Entry>()
        entries.add(
            DavXml.Entry(
                DavPath.href(code, rel, node.isDir), node.isDir, node.size, node.mtime,
                if (rel.isEmpty()) scope.label else DavPath.nameOf(rel),
            )
        )
        if (node.isDir && depth != "0") {
            val kids = runCatching { runBlocking { vfs.list(path) } }.getOrDefault(emptyList())
            for (k in kids) {
                val childRel = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                entries.add(
                    DavXml.Entry(
                        DavPath.href(code, childRel, k.isDir), k.isDir, k.size, k.mtime, k.name,
                    )
                )
            }
        }
        val unknown = if (DavXml.isAllProp(body)) emptyList() else DavXml.unknownProps(body)
        val xml = DavXml.multiStatus(entries, unknown).toByteArray(Charsets.UTF_8)
        status(
            out, 207, "Multi-Status",
            listOf("Content-Type: text/xml; charset=\"utf-8\"", "Content-Length: ${xml.size}"),
        )
        out.write(xml)
    }

    private fun get(
        out: OutputStream,
        path: VPath,
        rel: String,
        headers: Map<String, String>,
        headOnly: Boolean,
    ) {
        val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull()
            ?: return status(out, 404, "Not Found")
        if (node.isDir) return status(out, 403, "Forbidden")

        val total = node.size
        val range = DavRange.parse(headers["range"], total)
        val type = DavXml.contentType(DavPath.nameOf(rel))
        val common = mutableListOf(
            "Content-Type: $type",
            "Last-Modified: ${DavXml.httpDate(node.mtime)}",
            // Advertised so a client will even try a partial request, which is what makes a
            // large file resumable rather than restarting from zero on a dropped connection.
            "Accept-Ranges: bytes",
        )
        if (range == null) {
            common.add("Content-Length: ${total.coerceAtLeast(0)}")
            status(out, 200, "OK", common)
            if (!headOnly) stream(path, out, 0, total)
        } else if (!range.satisfiable) {
            status(out, 416, "Range Not Satisfiable", listOf("Content-Range: bytes */$total"))
        } else {
            common.add("Content-Length: ${range.length}")
            common.add("Content-Range: bytes ${range.start}-${range.end}/$total")
            status(out, 206, "Partial Content", common)
            if (!headOnly) stream(path, out, range.start, range.length)
        }
    }

    private fun stream(path: VPath, out: OutputStream, skip: Long, length: Long) {
        runCatching {
            runBlocking { vfs.openRead(path) }.use { input ->
                var toSkip = skip
                while (toSkip > 0) {
                    val n = input.skip(toSkip)
                    if (n <= 0) break
                    toSkip -= n
                }
                val buf = ByteArray(64 * 1024)
                var remaining = if (length >= 0) length else Long.MAX_VALUE
                while (remaining > 0) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private fun put(
        out: OutputStream,
        path: VPath,
        input: InputStream,
        length: Long,
        address: String,
    ) {
        val existed = runCatching { runBlocking { vfs.stat(path) } }.getOrNull() != null
        val ok = runCatching {
            runBlocking { vfs.openWrite(path, append = false) }.use { sink ->
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }.isSuccess
        clients[address]?.let { it.writes++ }
        if (!ok) return status(out, 500, "Internal Server Error")
        // 201 for a new resource, 204 for one that was replaced. Explorer uses the difference
        // when it decides whether its copy dialog succeeded.
        status(out, if (existed) 204 else 201, if (existed) "No Content" else "Created",
            listOf("Content-Length: 0"))
    }

    private fun delete(out: OutputStream, path: VPath) {
        val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull()
            ?: return status(out, 404, "Not Found")
        val ok = runCatching { runBlocking { vfs.delete(path, recursive = node.isDir) } }.isSuccess
        status(out, if (ok) 204 else 500, if (ok) "No Content" else "Internal Server Error",
            listOf("Content-Length: 0"))
    }

    private fun mkcol(out: OutputStream, path: VPath) {
        if (runCatching { runBlocking { vfs.stat(path) } }.getOrNull() != null) {
            return status(out, 405, "Method Not Allowed")
        }
        val ok = runCatching { runBlocking { vfs.create(path, isDir = true) } }.isSuccess
        status(out, if (ok) 201 else 409, if (ok) "Created" else "Conflict",
            listOf("Content-Length: 0"))
    }

    private fun moveOrCopy(
        out: OutputStream,
        from: VPath,
        headers: Map<String, String>,
        move: Boolean,
    ) {
        val dest = headers["destination"] ?: return status(out, 400, "Bad Request")
        // The Destination header is an absolute URL. Only its path matters, and it goes
        // through the same resolver as everything else - a MOVE is otherwise a way to write
        // outside the share using a header nobody checked.
        val destPath = dest.substringAfter("://").substringAfter('/').let { "/$it" }
        val r = DavPath.resolve(destPath, code)
        if (r !is DavPath.Resolved.Ok) return status(out, 403, "Forbidden")
        val base = root ?: return status(out, 503, "Service Unavailable")
        val target = childOf(base, r.rel)
        if (runCatching { runBlocking { vfs.stat(from) } }.getOrNull() == null) {
            return status(out, 404, "Not Found")
        }
        val existed = runCatching { runBlocking { vfs.stat(target) } }.getOrNull() != null
        if (existed && headers["overwrite"]?.uppercase() == "F") {
            return status(out, 412, "Precondition Failed")
        }
        val parent = targetParent(base, r.rel) ?: return status(out, 409, "Conflict")
        // The new NAME goes through as a rename rather than being assumed equal to the old
        // one. Explorer renames a file by MOVEing it onto a different name in the same folder,
        // so a move that ignores the destination name renames nothing and silently no-ops.
        val newName = DavPath.nameOf(r.rel)
        val ok = runCatching {
            runBlocking {
                if (move) vfs.move(from, parent, rename = newName)
                else vfs.copy(from, parent, rename = newName)
            }
        }.isSuccess
        status(out, if (!ok) 500 else if (existed) 204 else 201,
            if (!ok) "Internal Server Error" else if (existed) "No Content" else "Created",
            listOf("Content-Length: 0"))
    }

    private fun lock(out: OutputStream, rel: String, headers: Map<String, String>, body: String) {
        if (!writable) {
            // A read-only share still has to answer LOCK, because Explorer asks before it will
            // show the drive as anything other than broken. Refusing the lock is the honest
            // answer and it maps to a drive that opens and will not accept writes.
            return status(out, 403, "Forbidden")
        }
        val timeout = DavLocks.timeoutSeconds(headers["timeout"])
        val token = locks.acquire(rel, timeout)
            ?: return status(out, 423, "Locked", listOf("Content-Length: 0"))
        val xml = DavXml.lockResponse(token, headers["depth"] ?: "0", timeout, "Filet")
            .toByteArray(Charsets.UTF_8)
        status(
            out, 200, "OK",
            listOf(
                "Content-Type: text/xml; charset=\"utf-8\"",
                "Lock-Token: <$token>",
                "Content-Length: ${xml.size}",
            ),
        )
        out.write(xml)
    }

    private fun unlock(out: OutputStream, rel: String, headers: Map<String, String>) {
        val token = headers["lock-token"]?.trim()?.removePrefix("<")?.removeSuffix(">")
        locks.release(rel, token)
        status(out, 204, "No Content", listOf("Content-Length: 0"))
    }

    private fun proppatch(out: OutputStream, rel: String) {
        // Understood, not stored. The VFS has no timestamp writer, and claiming success would
        // make Explorer believe it had set a date it can then read back differently.
        val xml = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:multistatus xmlns:D=\"DAV:\">\n<D:response>\n" +
                "<D:href>" + DavXml.esc(DavPath.href(code, rel, false)) + "</D:href>\n" +
                "<D:propstat>\n<D:prop/>\n" +
                "<D:status>HTTP/1.1 403 Forbidden</D:status>\n</D:propstat>\n" +
                "</D:response>\n</D:multistatus>\n"
            ).toByteArray(Charsets.UTF_8)
        status(
            out, 207, "Multi-Status",
            listOf("Content-Type: text/xml; charset=\"utf-8\"", "Content-Length: ${xml.size}"),
        )
        out.write(xml)
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────

    private fun childOf(base: VPath, rel: String): VPath =
        rel.split('/').filter { it.isNotEmpty() }.fold(base) { acc, seg -> acc.child(seg) }

    private fun targetParent(base: VPath, rel: String): VPath? =
        DavPath.parentOf(rel)?.let { childOf(base, it) }

    private fun touch(address: String, agent: String) {
        val now = System.currentTimeMillis()
        val existing = clients[address]
        if (existing == null) {
            clients[address] = DavClient(address, agent.ifBlank { "unknown" }, now, now)
            onEvent(state())
        } else {
            existing.lastSeen = now
            existing.reads++
        }
    }

    private fun status(
        out: OutputStream,
        code: Int,
        reason: String,
        headers: List<String> = listOf("Content-Length: 0"),
    ) {
        val b = StringBuilder("HTTP/1.1 $code $reason\r\n")
        for (h in headers) b.append(h).append("\r\n")
        b.append("\r\n")
        out.write(b.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = try { input.read() } catch (e: Exception) { return null }
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
            if (b.length > MAX_LINE) return null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val out = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return out
            if (++count > MAX_HEADERS) return null
            val i = line.indexOf(':')
            if (i <= 0) continue
            out[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
    }

    private fun readExactly(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = try { input.read(buf, read, n - read) } catch (e: Exception) { -1 }
            if (got < 0) break
            read += got
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun drain(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val got = try {
                input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            } catch (e: Exception) { -1 }
            if (got < 0) return
            remaining -= got
        }
    }

    companion object {
        /**
         * Above 1024 because Android forbids a privileged bind without root, and deliberately
         * not 80 or 8080 - both are commonly taken by something else on a phone.
         */
        const val DEFAULT_PORT = 8321

        /** No vowels, and nothing that looks like anything else in Explorer's address bar. */
        private const val ALPHABET = "23456789bcdfghjkmnpqrstvwxz"

        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64

        /** Bodies above this are drained rather than held: only PUT streams, and it streams. */
        private const val MAX_BODY_READ = 256 * 1024
    }

    private val WRITE_METHODS = setOf("PUT", "DELETE", "MKCOL", "MOVE", "COPY", "PROPPATCH")
}
