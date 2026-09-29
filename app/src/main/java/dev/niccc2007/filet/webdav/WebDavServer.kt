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

/** One share, as the card draws it: what it is set to, and what it is doing. */
data class DavShareState(
    val share: DavShare,
    val running: Boolean = false,
    /**
     * The code it is answering on, or empty when it is not running.
     *
     * Not the same as `share.code`: that is the pinned one, which may be null, and a running share
     * always has a code whether it was pinned or minted.
     */
    val code: String = "",
    val writable: Boolean = false,
    val clients: List<DavClient> = emptyList(),
    val lastActivity: Long = 0,
) {
    val id: Long get() = share.id
}

/** What the hosting card draws itself from. */
data class DavState(
    /** The port every running share is served on. Zero when nothing is listening. */
    val port: Int = 0,
    /** The port that will be tried on the next start. */
    val preferredPort: Int = 0,
    val shares: List<DavShareState> = emptyList(),
    /** Shares whose pinned codes collide, so the card can say which rows are in conflict. */
    val clashes: Set<Long> = emptySet(),
) {
    /** True when anything at all is listening. */
    val running: Boolean get() = shares.any { it.running }

    val liveShares: List<DavShareState> get() = shares.filter { it.running }

    /**
     * Every desktop on every share.
     *
     * One address can be mounted on two shares and counts once: the question this answers is how
     * many machines are looking at this phone, not how many mounts exist.
     */
    val clients: List<DavClient> get() = liveShares.flatMap { it.clients }.distinctBy { it.address }
}

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
    /**
     * Everything the index knows, for the flat views.
     *
     * A lambda rather than the index itself, so this class keeps knowing only about the VFS
     * and the caller decides what "everything" means and what it costs.
     */
    private val indexed: (() -> List<DavViews.Entry>)? = null,
) {
    /**
     * One thread per live connection, created on demand and reaped when idle.
     *
     * This was `newFixedThreadPool(4)`, and it was the single worst bug in hosting - it looked
     * exactly like the network being slow and it was neither the network nor the protocol.
     *
     * Windows' redirector opens a **new connection per request** and leaves the old ones open.
     * Every connection holds its worker for the whole session, up to [SOCKET_TIMEOUT_MS]. Four
     * workers, minus one permanently consumed by the accept loop, left THREE. A single folder
     * listing in Explorer opens nine connections; the fourth onwards sat in the queue untouched
     * until an earlier connection hit its 30-second timeout and let go.
     *
     * Measured on the wire through a logging proxy: every reply arrived within milliseconds of a
     * previous connection closing, after waits of 29.81s, 29.96s and 29.82s. One `Get-ChildItem`
     * of a six-entry folder took 59,998ms - two full timeout rounds - while the same PROPFIND
     * took 34ms over curl. Nothing was slow. Everything was queued.
     *
     * Cached rather than a bigger fixed number, because the right count is "however many the
     * client opened" and that is a property of the client, not something to guess. Bounded all
     * the same: this listens on a LAN, and an unbounded pool turns a rude client into an
     * out-of-memory kill.
     */
    private val pool: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        0,
        MAX_CONNECTIONS,
        60L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
    ).apply {
        // A refused connection is closed at once rather than queued. A client that is told no
        // retries; a client left hanging is the bug this whole class of fault comes from.
        setRejectedExecutionHandler { task, _ ->
            (task as? CloseableTask)?.closeQuietly()
        }
    }

    /** So the rejection handler can close the socket it was never going to serve. */
    private class CloseableTask(private val client: Socket, private val body: () -> Unit) : Runnable {
        override fun run() = body()
        fun closeQuietly() { runCatching { client.close() } }
    }
    private var socket: ServerSocket? = null
    private val generation = AtomicLong(0)
    @Volatile private var running = false

    /**
     * The shares that are listening right now, by share id.
     *
     * One socket carries all of them. [DavPath] has always resolved `/a/<code>/rest`, so the code
     * is the first thing every request names and is therefore the share selector - nothing had to
     * be invented to serve more than one. A socket per share would cost a port to open, an
     * announcement and a lifecycle each, and buy nothing the code was not already doing.
     */
    private val live = ConcurrentHashMap<Long, LiveShare>()

    private val locks = DavLocks()
    private val random = SecureRandom()

    /**
     * The configured shares, running or not. Persisted.
     *
     * Assigning this keeps whatever is already live untouched: editing a share's label while a
     * desktop has it mounted must not drop the mount. A setting that only takes effect on the next
     * start says so in the card rather than being applied under a live client.
     */
    @Volatile var shares: List<DavShare> = listOf(DavShares.default())
        set(value) {
            field = value
            store?.shares = DavShares.encode(value)
            onEvent(state())
        }

    /**
     * Called after something mounted on this phone changed a file.
     *
     * No filesystem watcher is involved and none is wanted: this server PERFORMED the write, so
     * it knows precisely what changed and when, with no polling, no inotify registrations across
     * a whole volume, and no window in which a change is missed. A watcher would be strictly less
     * accurate and strictly more expensive for the one case that matters here.
     *
     * Fired for writes only. A desktop merely reading the share must not be able to make the
     * phone re-list folders.
     */
    var onRemoteWrite: (() -> Unit)? = null

    /**
     * Announces each running share on the local network, or null in a test.
     *
     * Set from the graph. Without it hosting still works and is simply silent, which is what it
     * was - the address had to be read off one phone and typed into another.
     */
    var beacon: DavBeacon? = null

    /**
     * Where the shares and the port are remembered between launches, or null in a test.
     *
     * The endpoint a desktop mounts is `http://<ip>:<port>/a/<code>`, and a mapped network drive
     * stores that whole string. Holding any part of it only in memory meant the URL moved every
     * time the app started and the mapping on the PC silently stopped resolving.
     */
    var store: DavSettingsStore? = null
        set(value) {
            field = value
            value?.let {
                preferredPort = it.port
                // Reads the stored list, and folds a pre-list installation into it. Without the
                // migration an upgrade would forget a pinned code and a chosen folder - which is
                // the mapped-drive breakage this persistence was added to stop.
                field = it
                shares = DavShares.migrate(it.shares, it.code, it.root)
            }
        }

    /** The port to try first. Zero means let the system choose. */
    @Volatile var preferredPort: Int = DEFAULT_PORT
        set(value) {
            field = value
            store?.port = value
        }

    var port: Int = 0
        private set

    /** One share as it is actually listening: its settings, where it landed, and who is on it. */
    inner class LiveShare(
        /**
         * The settings this share is running on.
         *
         * A var, so a policy change reaches a running share instead of waiting for a restart -
         * but [root] and [code] below are captured at start and are NOT re-read from it. Those two
         * are the endpoint a desktop has mounted; moving them under a live mount leaves it holding
         * paths that no longer resolve. See `DavShares.needsRestart`.
         */
        @Volatile var share: DavShare,
        val root: VPath,
        /** The code this share answers on, which may be pinned or freshly minted. */
        val code: String,
    ) {
        val id: Long get() = share.id
        val clients = ConcurrentHashMap<String, DavClient>()
        val lastActivity = AtomicLong(System.currentTimeMillis())

        /**
         * Whether the desktop may change files, for this session only.
         *
         * Deliberately not read back from storage - see `DavShares.decode`. Turning a phone into a
         * writable network drive is a decision for the session in front of you.
         */
        @Volatile var writable: Boolean = share.writable

        val views: Boolean get() = share.views
    }

    fun state(): DavState = DavState(
        port = port,
        preferredPort = preferredPort,
        shares = shares.map { s ->
            val l = live[s.id]
            DavShareState(
                share = s,
                running = l != null,
                code = l?.code.orEmpty(),
                writable = l?.writable ?: false,
                clients = l?.clients?.values?.sortedBy { it.since } ?: emptyList(),
                lastActivity = l?.lastActivity?.get() ?: 0L,
            )
        },
        clashes = DavShares.codeClashes(shares),
    )

    /**
     * Start one share, opening the socket if this is the first.
     *
     * The code is settled here rather than at bind time because a pinned code has to survive and a
     * fresh one has to not collide with a share already up.
     */
    fun start(id: Long): DavState {
        val share = shares.firstOrNull { it.id == id } ?: return state()
        if (live.containsKey(id)) return state()
        val base = share.customRoot?.let { runCatching { VPath.parse(it) }.getOrNull() }
            ?: rootFor(share.scope)
            ?: return state()
        if (!openSocket()) return state()

        // Short and unambiguous: it is typed into an address bar by hand, sometimes read off a
        // screen across a room. No vowels, so it cannot spell anything, and no characters that
        // look like each other in the fonts Explorer uses.
        val taken = live.values.map { it.code }.toSet()
        val wanted = share.code?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } }
        val code = when {
            // A pinned code that another running share already answers on cannot be honoured -
            // the first one would shadow this one and this share would be silently unreachable.
            wanted != null && wanted !in taken -> wanted
            else -> generateSequence { mint() }.first { it !in taken }
        }
        live[id] = LiveShare(share, base, code)
        announce(live.getValue(id))
        onEvent(state())
        return state()
    }

    /** Start every share that asked to come up on its own. */
    fun startAutoShares(): DavState {
        for (s in DavShares.autoStarting(shares)) start(s.id)
        return state()
    }

    /** Stop one share, closing the socket when it was the last. */
    fun stop(id: Long): DavState {
        val l = live.remove(id) ?: return state()
        runCatching { beacon?.stopAdvertising(l.id) }
        if (live.isEmpty()) closeSocket()
        onEvent(state())
        return state()
    }

    /** Stop everything. What the notification's Stop button and a teardown mean. */
    fun stop(): DavState {
        for (id in live.keys.toList()) {
            live.remove(id)
            runCatching { beacon?.stopAdvertising(id) }
        }
        closeSocket()
        onEvent(state())
        return state()
    }

    /**
     * Push changed settings onto a share that is already listening.
     *
     * Everything except the root and the code, which are the endpoint - see
     * `DavShares.needsRestart` for why those two alone wait.
     */
    fun applyLive(share: DavShare) {
        val l = live[share.id] ?: return
        l.share = share
        l.writable = share.writable
        runCatching { announce(l) }
        onEvent(state())
    }

    /**
     * Turn writing on or off for a share that is already listening.
     *
     * The only per-share setting that reaches a live share. Everything else is part of the endpoint
     * and changing it under a mounted drive would leave the desktop holding paths that no longer
     * resolve - but revoking write access has to be instant or it is not worth having.
     */
    fun setWritable(id: Long, on: Boolean) {
        live[id]?.writable = on
        runCatching { live[id]?.let { announce(it) } }
        onEvent(state())
    }

    /** Whether any share is up. The one question the foreground service asks. */
    fun isRunning(): Boolean = live.isNotEmpty()

    private fun openSocket(): Boolean {
        if (running && socket != null) return true
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
                return false
            }
        }
        socket = s
        port = s.localPort
        running = true
        val mine = generation.incrementAndGet()
        // Its own thread, never a worker. Taking one from the pool meant the accept loop sat on a
        // worker for the entire life of the server, so a pool of four served three connections.
        Thread({ accept(s, mine) }, "filet-dav-accept").apply { isDaemon = true }.start()
        return true
    }

    private fun closeSocket() {
        running = false
        generation.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        locks.clear()
    }

    private fun mint(): String =
        (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * Announce a share, so another phone can add it without anybody reading out an address.
     *
     * The code is NOT in the announcement - see DavBeacon. What travels is where to knock.
     * Called once the port is settled, because the announcement carries it and the preferred port
     * is often taken.
     */
    private fun announce(l: LiveShare) {
        val b = beacon ?: return
        runCatching {
            b.advertise(
                id = l.id,
                label = l.share.label,
                port = port,
                basePath = "/a/",
                scope = l.share.rootLabel,
                writable = l.writable,
            )
        }
    }

    /**
     * Close any share that nothing has touched for its own idle window.
     *
     * Per share, not per socket: one share being busy must not hold another open, which is why
     * activity is attributed after the code is resolved rather than when the connection arrives.
     * A share whose window is zero or less never closes itself - that is what "stays up while
     * Filet runs" means, and the socket lives as long as the process does.
     */
    fun stopIfIdle(now: Long = System.currentTimeMillis()) {
        for (l in live.values.toList()) {
            val window = l.share.idleMinutes
            if (window <= 0) continue
            if (now - l.lastActivity.get() >= window * 60_000L) stop(l.id)
        }
    }

    fun kick(address: String) {
        for (l in live.values) l.clients.remove(address)
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
            pool.execute(
                CloseableTask(client) {
                    runCatching { serve(client) }
                    runCatching { client.close() }
                },
            )
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        var served = 0
        val input = PushbackInputStream(socket.getInputStream().buffered(), 8)
        val out = BufferedOutputStream(socket.getOutputStream())
        val address = socket.inetAddress?.hostAddress.orEmpty()

        // One connection carries many requests: Explorer opens a socket and walks the tree
        // down it. Closing after one turns a folder listing into a hundred handshakes.
        while (running) {
            // The first request gets the full window; waiting for a SECOND one on the same
            // connection gets much less. Explorer reuses a connection within milliseconds when it
            // reuses one at all, and the rest of the time it abandons it - so a long idle wait
            // here buys nothing and costs a worker.
            if (served == 1) socket.soTimeout = KEEPALIVE_IDLE_MS
            val line = readLine(input) ?: return
            if (line.isBlank()) return
            served++
            val parts = line.split(' ')
            if (parts.size < 3) { status(out, 400, "Bad Request"); out.flush(); return }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = readHeaders(input) ?: return
            // Who reads this body is DavBody's decision, not a length comparison here. The
            // loop used to buffer anything under 256 KB - including a PUT, whose handler was then
            // given an empty stream after it had already truncated the file.
            val plan = DavBody.plan(method, headers, MAX_BODY_READ)
            val length = DavBody.declaredLength(headers) ?: 0L
            val body = when (plan) {
                is DavBody.Plan.Buffer -> readExactly(input, plan.length)
                else -> ""
            }
            if (plan is DavBody.Plan.Drain) {
                if (!drainBody(input, plan.length)) return
            }

            // No touch here. Which share this request is for is not known until its code has
            // been resolved, and attributing traffic to the wrong share is how a per-share idle
            // clock stops meaning anything.
            handle(method, target, headers, body, input, out, address, length, plan)
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
        plan: DavBody.Plan,
    ) {
        // OPTIONS is answered before the path is resolved. Explorer sends it at the root of
        // the server to find out whether it speaks WebDAV at all, and a 403 there ends the
        // conversation before the mount is ever attempted.
        if (method == "OPTIONS") return options(out)

        when (val r = DavPath.resolve(target, live.values.map { it.code })) {
            is DavPath.Resolved.Forbidden -> return refuse(out, input, headers, 403, "Forbidden")
            is DavPath.Resolved.Refused -> return refuse(out, input, headers, 400, "Bad Request")
            is DavPath.Resolved.Ok -> {
                // The code named a share, and every per-share decision below comes off THAT share
                // rather than off the server. This is the whole of what makes several shares safe:
                // a request cannot reach a root, or a write permission, other than its own.
                val sh = live.values.firstOrNull { it.code == r.code }
                    ?: return status(out, 503, "Service Unavailable")

                // Activity is attributed here, after the share is known - not when the connection
                // arrived. One share being busy must not hold another open, and a request with a
                // wrong code must not hold anything open at all.
                sh.lastActivity.set(System.currentTimeMillis())
                touch(sh, address, headers["user-agent"].orEmpty())

                val base = sh.root
                val needsWrite = method in WRITE_METHODS
                // The write gate is unchanged and still comes before anything touches the file.
                // What changed is that a refusal now clears the body off the connection, instead
                // of leaving it to be read as the next request line.
                if (needsWrite && !sh.writable) return refuse(out, input, headers, 403, "Forbidden")

                // The flat views are read-only by construction: they are a rendering of where
                // files are, not a place files can be put. A write aimed at one would have to
                // invent a destination.
                if (sh.views && r.rel.split('/').firstOrNull() == DavViews.ROOT) {
                    if (needsWrite) return refuse(out, input, headers, 403, "Forbidden")
                    return serveView(sh, method, out, r.rel, headers, body)
                }

                val path = childOf(base, r.rel)
                when (method) {
                    "PROPFIND" -> propfind(sh, out, path, r.rel, headers, body)
                    "HEAD" -> get(out, path, r.rel, headers, headOnly = true)
                    "GET" -> get(out, path, r.rel, headers, headOnly = false)
                    "PUT" -> put(sh, out, path, input, headers, plan, address)
                    "DELETE" -> delete(out, path)
                    "MKCOL" -> mkcol(out, path)
                    "MOVE" -> moveOrCopy(sh, out, path, headers, move = true)
                    "COPY" -> moveOrCopy(sh, out, path, headers, move = false)
                    "LOCK" -> lock(sh, out, r.rel, headers, body)
                    "UNLOCK" -> unlock(out, r.rel, headers)
                    // Explorer PROPPATCHes timestamps on every file it writes. Refusing makes
                    // a copy into the drive report failure after the bytes already landed, so
                    // the honest answer is that the request was understood and the property is
                    // not stored - which is what a 207 of 403s says.
                    "PROPPATCH" -> proppatch(sh, out, r.rel)
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
        sh: LiveShare,
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
                DavPath.href(sh.code, rel, node.isDir), node.isDir, node.size, node.mtime,
                if (rel.isEmpty()) sh.share.label else DavPath.nameOf(rel),
                quota = if (node.isDir) quotaFor(sh) else null,
            )
        )
        if (node.isDir && depth != "0") {
            // At the share root, the views appear as one more folder. Without this entry
            // Explorer never learns the path exists, because a client only walks what it is
            // told about - it cannot guess a name.
            if (rel.isEmpty() && sh.views && indexed != null) {
                entries.add(
                    DavXml.Entry(
                        DavPath.href(sh.code, DavViews.ROOT, true), true, 0,
                        System.currentTimeMillis(), DavViews.ROOT,
                    )
                )
            }
            val kids = runCatching { runBlocking { vfs.list(path) } }.getOrDefault(emptyList())
            for (k in kids) {
                val childRel = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                entries.add(
                    DavXml.Entry(
                        DavPath.href(sh.code, childRel, k.isDir), k.isDir, k.size, k.mtime, k.name,
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

    /**
     * Answer with a status, and take the request's body off the connection first.
     *
     * A refused write still has its body on the wire unless the client was holding it back behind
     * `Expect: 100-continue`. Leaving it there desynchronises the connection: the next request
     * line gets read out of the file contents, so one 403 corrupts the request after it.
     *
     * The refusal is deliberately sent WITHOUT a `100 Continue` first, which is what stops the
     * bytes ever being sent in the case where the client asked.
     */
    private fun refuse(
        out: OutputStream,
        input: InputStream,
        headers: Map<String, String>,
        code: Int,
        reason: String,
    ) {
        status(out, code, reason)
        val plan = DavBody.drainAfterRefusal(headers)
        if (plan is DavBody.Plan.Drain) drainBody(input, plan.length)
    }

    /**
     * Take a body off the connection. Returns false when the connection cannot be trusted after.
     *
     * A null length is a chunked body, which has to be walked frame by frame - there is no count
     * to skip.
     */
    private fun drainBody(input: InputStream, length: Long?): Boolean {
        if (length == null) return runCatching { readChunked(input, null) }.isSuccess
        if (length <= 0) return true
        return runCatching { drain(input, length); true }.getOrDefault(false)
    }

    /**
     * Read a chunked body, optionally writing it out.
     *
     * Each frame is a hex length, CRLF, that many bytes, CRLF, ending with a zero-length frame.
     * A null [sink] walks the frames and discards them, which is how a chunked body gets drained.
     */
    private fun readChunked(input: InputStream, sink: OutputStream?): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val line = readLine(input) ?: break
            // A chunk extension after a semicolon is legal and ignorable.
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: break
            if (size == 0L) {
                // Trailers, then the terminating blank line.
                while (true) {
                    val t = readLine(input) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            var remaining = size
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) return total
                sink?.write(buf, 0, n)
                remaining -= n
                total += n
            }
            // The CRLF that closes the frame.
            readLine(input)
        }
        return total
    }

    /**
     * Free and used for a share, as RFC 4331 wants it.
     *
     * Read from the volume the share is rooted on, and cached briefly: Explorer asks for it on
     * every folder it opens, and reading free space is a syscall each time.
     */
    private fun quotaFor(sh: LiveShare): DavQuota.Report? {
        val now = System.currentTimeMillis()
        val cached = quotaCache
        if (cached != null && now - quotaAt < QUOTA_CACHE_MS) return cached
        val report = runCatching {
            runBlocking {
                DavQuota.of(
                    totalBytes = vfs.totalSpace(sh.root),
                    usableBytes = vfs.freeSpace(sh.root),
                )
            }
        }.getOrNull()
        quotaCache = report
        quotaAt = now
        return report
    }

    @Volatile private var quotaCache: DavQuota.Report? = null
    @Volatile private var quotaAt: Long = 0L

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

    /**
     * Write a file the client is sending.
     *
     * Reached only after the write gate in [handle] has passed, so by here the share permits
     * writing. Three things this has to get right that it previously did not:
     *
     *  1. **It owns the stream.** The connection loop no longer reads the body - see [DavBody].
     *  2. **All three framings.** A counted body, a chunked body, and an empty one.
     *  3. **The original survives a failure.** The bytes go to a temporary sibling and are moved
     *     into place only once they have all arrived. `openWrite(append = false)` truncates on
     *     the spot, so writing straight to the target destroyed the existing file before knowing
     *     whether the new one would arrive - which is how a dropped connection used to leave a
     *     half-written or empty file where a good one had been.
     */
    private fun put(
        sh: LiveShare,
        out: OutputStream,
        path: VPath,
        input: InputStream,
        headers: Map<String, String>,
        plan: DavBody.Plan,
        address: String,
    ) {
        val existed = runCatching { runBlocking { vfs.stat(path) } }.getOrNull() != null

        // Only now, after the gate. Telling a client to proceed and then refusing it would mean
        // the body is on the wire for nothing.
        if (DavBody.expectsContinue(headers)) {
            runCatching {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }

        val declared = (plan as? DavBody.Plan.Stream)?.length
        val parent = path.parent ?: return status(out, 409, "Conflict")
        val tempName = ".filet-part-" + java.lang.Long.toHexString(System.nanoTime())
        val temp = parent.child(tempName)

        val written = runCatching {
            runBlocking { vfs.openWrite(temp, append = false) }.use { sink ->
                if (declared == null) readChunked(input, sink) else copyExactly(input, sink, declared)
            }
        }.getOrNull()

        // A short body is a failed transfer, not a small file. Accepting it would replace a good
        // file with a truncated one and report success.
        val complete = written != null && (declared == null || written == declared)
        if (!complete) {
            runCatching { runBlocking { vfs.delete(temp) } }
            return status(out, 500, "Internal Server Error")
        }

        val moved = runCatching {
            runBlocking {
                if (existed) vfs.delete(path)
                vfs.move(temp, parent, rename = DavPath.nameOf(path.path))
            }
        }.isSuccess
        if (!moved) {
            runCatching { runBlocking { vfs.delete(temp) } }
            return status(out, 500, "Internal Server Error")
        }

        sh.clients[address]?.let { it.writes++ }
        onRemoteWrite?.invoke()
        // 201 for a new resource, 204 for one that was replaced. Explorer uses the difference
        // when it decides whether its copy dialog succeeded.
        status(out, if (existed) 204 else 201, if (existed) "No Content" else "Created",
            listOf("Content-Length: 0"))
    }

    /** Copy exactly [length] bytes, returning how many actually arrived. */
    private fun copyExactly(input: InputStream, sink: OutputStream, length: Long): Long {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        var total = 0L
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            sink.write(buf, 0, n)
            remaining -= n
            total += n
        }
        return total
    }

    private fun delete(out: OutputStream, path: VPath) {
        val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull()
            ?: return status(out, 404, "Not Found")
        val ok = runCatching { runBlocking { vfs.delete(path, recursive = node.isDir) } }.isSuccess
        if (ok) onRemoteWrite?.invoke()
        status(out, if (ok) 204 else 500, if (ok) "No Content" else "Internal Server Error",
            listOf("Content-Length: 0"))
    }

    private fun mkcol(out: OutputStream, path: VPath) {
        if (runCatching { runBlocking { vfs.stat(path) } }.getOrNull() != null) {
            return status(out, 405, "Method Not Allowed")
        }
        val ok = runCatching { runBlocking { vfs.create(path, isDir = true) } }.isSuccess
        if (ok) onRemoteWrite?.invoke()
        status(out, if (ok) 201 else 409, if (ok) "Created" else "Conflict",
            listOf("Content-Length: 0"))
    }

    private fun moveOrCopy(
        sh: LiveShare,
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
        // Resolved against THIS share's code alone, not against every live code. A MOVE whose
        // Destination names a different share would otherwise write across a share boundary using
        // a header, which is exactly the hole this resolver exists to close.
        val r = DavPath.resolve(destPath, sh.code)
        if (r !is DavPath.Resolved.Ok) return status(out, 403, "Forbidden")
        val base = sh.root
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
        if (ok) onRemoteWrite?.invoke()
        status(out, if (!ok) 500 else if (existed) 204 else 201,
            if (!ok) "Internal Server Error" else if (existed) "No Content" else "Created",
            listOf("Content-Length: 0"))
    }

    private fun lock(
        sh: LiveShare,
        out: OutputStream,
        rel: String,
        headers: Map<String, String>,
        body: String,
    ) {
        if (!sh.writable) {
            // A read-only share still has to answer LOCK, because Explorer asks before it will
            // show the drive as anything other than broken. Refusing the lock is the honest
            // answer and it maps to a drive that opens and will not accept writes.
            return status(out, 403, "Forbidden")
        }
        val timeout = DavLocks.timeoutSeconds(headers["timeout"])
        // The token the client says it already holds. A LOCK carrying one is a REFRESH, not a
        // new lock, and refusing it is what made every large Windows copy fail part way.
        val presented = DavLocks.tokenIn(headers["if"])
        val token = locks.acquire(rel, timeout, presented)
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

    private fun proppatch(sh: LiveShare, out: OutputStream, rel: String) {
        // Understood, not stored. The VFS has no timestamp writer, and claiming success would
        // make Explorer believe it had set a date it can then read back differently.
        val xml = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:multistatus xmlns:D=\"DAV:\">\n<D:response>\n" +
                "<D:href>" + DavXml.esc(DavPath.href(sh.code, rel, false)) + "</D:href>\n" +
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

    /**
     * Serve the flat views.
     *
     * Three shapes: the views root, which lists the views as folders; a view, which lists the
     * files in it; and a file inside a view, which is the real file wherever it lives. The
     * third is what makes this a path renderer rather than a listing - opening it opens the
     * file, from a name that was never in that folder.
     */
    private fun serveView(
        sh: LiveShare,
        method: String,
        out: OutputStream,
        rel: String,
        headers: Map<String, String>,
        body: String,
    ) {
        val source = indexed ?: return status(out, 404, "Not Found")
        val now = System.currentTimeMillis()

        if (DavViews.isRoot(rel)) {
            if (method != "PROPFIND") return status(out, 405, "Method Not Allowed")
            val entries = ArrayList<DavXml.Entry>()
            entries.add(DavXml.Entry(DavPath.href(sh.code, rel, true), true, 0, now, DavViews.ROOT))
            if ((headers["depth"] ?: "1") != "0") {
                for (k in DavViews.Kind.entries) {
                    val childRel = DavViews.ROOT + "/" + k.folder
                    entries.add(
                        DavXml.Entry(DavPath.href(sh.code, childRel, true), true, 0, now, k.folder)
                    )
                }
            }
            return multi(out, entries, body)
        }

        val kind = DavViews.viewOf(rel) ?: return status(out, 404, "Not Found")
        val built = DavViews.build(kind, runCatching { source() }.getOrDefault(emptyList()))
        val file = DavViews.fileIn(rel)

        if (file == null) {
            if (method != "PROPFIND") return status(out, 405, "Method Not Allowed")
            val entries = ArrayList<DavXml.Entry>()
            entries.add(DavXml.Entry(DavPath.href(sh.code, rel, true), true, 0, now, kind.folder))
            if ((headers["depth"] ?: "1") != "0") {
                for (e in built) {
                    entries.add(
                        DavXml.Entry(
                            DavPath.href(sh.code, "$rel/${e.name}", false),
                            false, e.size, e.mtime, e.name,
                        )
                    )
                }
            }
            return multi(out, entries, body)
        }

        // Matched on the name the view gave it, which after disambiguation is not the name on
        // disk - so the entry carries the real path and the lookup goes through the entry.
        val entry = built.firstOrNull { it.name == file }
            ?: return status(out, 404, "Not Found")
        when (method) {
            "PROPFIND" -> multi(
                out,
                listOf(
                    DavXml.Entry(
                        DavPath.href(sh.code, rel, false), false, entry.size, entry.mtime, entry.name,
                    )
                ),
                body,
            )
            "HEAD" -> get(out, entry.path, entry.name, headers, headOnly = true)
            "GET" -> get(out, entry.path, entry.name, headers, headOnly = false)
            else -> status(out, 405, "Method Not Allowed")
        }
    }

    private fun multi(out: OutputStream, entries: List<DavXml.Entry>, body: String) {
        val unknown = if (DavXml.isAllProp(body)) emptyList() else DavXml.unknownProps(body)
        val xml = DavXml.multiStatus(entries, unknown).toByteArray(Charsets.UTF_8)
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

    private fun touch(sh: LiveShare, address: String, agent: String) {
        val now = System.currentTimeMillis()
        val existing = sh.clients[address]
        if (existing == null) {
            sh.clients[address] = DavClient(address, agent.ifBlank { "unknown" }, now, now)
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

        /** How long a free-space reading is reused. Explorer asks per folder. */
        private const val QUOTA_CACHE_MS = 5_000L

        private const val SOCKET_TIMEOUT_MS = 30_000

        /**
         * How long an already-served connection waits for another request before letting go.
         *
         * Short on purpose. A connection sitting idle is holding a worker, and Windows opens far
         * more connections than it reuses.
         */
        private const val KEEPALIVE_IDLE_MS = 5_000

        /**
         * The ceiling on live connections.
         *
         * Comfortably above what any desktop opens for one listing (nine was measured) and low
         * enough that a misbehaving client on the LAN cannot exhaust memory.
         */
        internal const val MAX_CONNECTIONS = 64
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64

        /** Bodies above this are drained rather than held: only PUT streams, and it streams. */
        private const val MAX_BODY_READ = 256 * 1024
    }

    private val WRITE_METHODS = setOf("PUT", "DELETE", "MKCOL", "MOVE", "COPY", "PROPPATCH")
}

/**
 * Where the endpoint's three parts are kept between launches.
 *
 * An interface rather than a Prefs reference, so `WebDavServer` keeps knowing only about the VFS
 * and its own protocol - the same reason its index access is a lambda. A unit test leaves it null
 * and the server behaves exactly as it did before.
 */
interface DavSettingsStore {
    /** The whole share list, encoded. Null or blank on a fresh install. */
    var shares: String?

    var code: String?
    var port: Int
    var root: dev.niccc2007.filet.vfs.VPath?
}
