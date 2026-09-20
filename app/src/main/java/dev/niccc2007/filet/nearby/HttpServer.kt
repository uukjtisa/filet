package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A browser or peer currently talking to us, for the live client list. */
data class NearbyClient(
    val address: String,
    val agent: String,
    val since: Long,
    var lastSeen: Long,
    var downloads: Int = 0,
    var state: String = "browsing",
)

data class ServerState(
    val running: Boolean = false,
    val port: Int = 0,
    /**
     * EVERY address this device is currently reachable on, best first - not one guess.
     *
     * A phone can be on Wi-Fi, running a hotspot and on mobile data at the same time, and
     * only the user knows which network the laptop they are trying to reach is on. Printing
     * a single address picked by enumeration order is how "I typed the URL and nothing
     * happened" happens.
     */
    val addresses: List<NetAddress> = emptyList(),
    val pin: String = "",
    val pinRequired: Boolean = true,
    val uploadsAllowed: Boolean = false,
    val clients: List<NearbyClient> = emptyList(),
    val idleStopMinutes: Int = 30,
    val lastActivity: Long = 0,
    val grants: List<AccessGrant> = emptyList(),
) {
    val address: String get() = addresses.firstOrNull()?.ip.orEmpty()
    val url: String get() = if (address.isEmpty()) "" else "http://$address:$port"
    val urls: List<String> get() = addresses.map { it.urlFor(port) }
}

/**
 * The LAN file server.
 *
 * Written directly on `ServerSocket` rather than on an HTTP library, for three reasons that
 * all matter here: range requests must stream from a `VFS` `InputStream` without buffering,
 * the zip endpoint must stream a multi-gigabyte selection without ever touching disk, and the
 * whole thing has to be small enough to audit - it is the one part of Filet that listens.
 *
 * Security, in the order it is enforced:
 *
 * 1. Nothing runs until the user starts it, and it stops itself when idle.
 * 2. **Serve by token.** `/f/<token>` resolves through [SharedSet]; no path is ever expressible
 *    in a URL, so traversal is not a thing that can be attempted.
 * 3. A **6-digit PIN**, on by default, rate limited to five attempts before the session dies.
 * 4. Uploads are off by default and land in quarantine, never in the shared set.
 */
class NearbyHttpServer(
    private val context: Context,
    private val vfs: Vfs,
    private val shared: SharedSet,
    private val deviceName: () -> String,
    private val onEvent: (ServerState) -> Unit,
    /**
     * Endpoints handed out to browsers that typed the PIN.
     *
     * Separate from [sessions] on purpose: a session is a cookie the user cannot see, a grant
     * is a URL they can see and revoke from the Nearby tab.
     */
    val grants: AccessGrants = AccessGrants(dev.niccc2007.filet.data.Prefs(context)),
) {
    private val pool = Executors.newFixedThreadPool(6)
    private var socket: ServerSocket? = null

    /**
     * Incremented on every start, so an accept loop can tell whether it is still the current
     * one. See AcceptLoop: without this a loop left over from the previous socket sees the
     * flag set true again by the next start and spins on a closed socket.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var running = false

    private val sessions = ConcurrentHashMap<String, Long>()
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()
    private val clients = ConcurrentHashMap<String, NearbyClient>()
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val random = SecureRandom()

    @Volatile var pin: String = ""
        private set

    /**
     * A PIN the user set, or null for "a fresh random one each session".
     *
     * Random-per-session is the safer default and stays the default. But a code that changes
     * every time is genuinely annoying when the same laptop connects ten times a day, and a
     * user who decides that trade-off for their own home network is entitled to make it.
     */
    @Volatile var fixedPin: String? = null
    @Volatile var pinRequired: Boolean = true
    @Volatile var uploadsAllowed: Boolean = false
    @Volatile var idleStopMinutes: Int = 30

    var port: Int = 0
        private set

    fun start(preferredPort: Int = DEFAULT_PORT): ServerState {
        if (running) return state()
        pin = fixedPin?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?: (100000 + random.nextInt(900000)).toString()
        sessions.clear()
        attempts.clear()
        clients.clear()

        // Fixed default, falling back if occupied - and the screen always shows the real one,
        // because a URL that is almost right is worse than no URL.
        var bound: ServerSocket? = null
        for (p in preferredPort until preferredPort + 20) {
            bound = runCatching { ServerSocket(p) }.getOrNull()
            if (bound != null) { port = p; break }
        }
        val server = bound ?: throw IOException("No free port between $preferredPort and ${preferredPort + 20}")
        socket = server
        running = true
        val mine = generation.incrementAndGet()
        lastActivity.set(System.currentTimeMillis())

        pool.execute {
            while (AcceptLoop.keepGoing(running, mine, generation.get(), server.isClosed)) {
                val client = runCatching { server.accept() }.getOrNull()
                if (client == null) {
                    // A throw from accept on a socket with no timeout means the socket is
                    // gone. Going back round is the spin this replaced.
                    if (AcceptLoop.stopOnAcceptFailure()) break else continue
                }
                pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
            }
            // Whatever ended this loop, the socket it owned is finished with.
            runCatching { server.close() }
        }
        return state().also(onEvent)
    }

    fun stop() {
        running = false
        // Moved on, so any accept loop still alive from this socket ends even if a start
        // arrives before it has looked at the flag again.
        generation.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        sessions.clear()
        clients.clear()
        shared.endSession()
        onEvent(state())
    }

    fun isRunning() = running

    /** Change the code mid-session. Existing grants keep working; the lock screen changes. */
    fun setPin(value: String?) {
        fixedPin = value
        if (running) {
            pin = value?.takeIf { it.length == 6 && it.all(Char::isDigit) }
                ?: (100000 + random.nextInt(900000)).toString()
            attempts.clear()
            onEvent(state())
        }
    }

    fun kick(address: String) {
        clients.remove(address)
        sessions.keys.filter { it.startsWith("$address|") }.forEach { sessions.remove(it) }
        onEvent(state())
    }

    /** True when the idle timeout has elapsed. The service polls this and stops us. */
    fun idleExpired(): Boolean =
        idleStopMinutes > 0 &&
            System.currentTimeMillis() - lastActivity.get() > idleStopMinutes * 60_000L

    fun state() = ServerState(
        running = running,
        port = port,
        addresses = NetAddresses.all(),
        pin = pin,
        pinRequired = pinRequired,
        uploadsAllowed = uploadsAllowed,
        clients = clients.values.sortedBy { it.since },
        idleStopMinutes = idleStopMinutes,
        lastActivity = lastActivity.get(),
        grants = grants.all.value,
    )

    // ────────────────────────── request handling ──────────────────────────

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }

        val address = socket.inetAddress?.hostAddress ?: "?"
        touch(address, headers["user-agent"] ?: "")

        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        // Two HTML endpoints, not one page that hides half of itself.
        //
        //   /            the lock screen. Nothing but the PIN box.
        //   /a/<token>/  the browser, reached only by a grant the PIN minted.
        //
        // Which means unlocking is a NAVIGATION - the lock screen is gone afterwards, because
        // it was a different document - rather than a div that got its `hidden` removed.
        val grantToken = if (path.startsWith("/a/")) path.removePrefix("/a/").substringBefore('/') else null
        val grant = grantToken?.let { grants.use(it) }
        val rest = if (grantToken != null) path.removePrefix("/a/$grantToken").ifEmpty { "/" } else path

        when {
            grantToken != null && grant == null ->
                // Revoked, expired, or never existed. Send them back to the lock screen
                // rather than a 404, which reads as "the whole thing is gone".
                redirect(out, "/")

            grantToken != null && rest == "/" && method == "GET" -> serveApp(out, grant!!)
            grantToken != null && rest == "/api/list" -> json(out, listJson(dirTokenOf(query)))
            grantToken != null && rest.startsWith("/t/") ->
                serveThumb(out, rest.removePrefix("/t/"))
            grantToken != null && rest.startsWith("/f/") ->
                serveFile(out, rest.removePrefix("/f/"), headers, address)
            grantToken != null && rest == "/z" -> serveZip(out, query, address)
            grantToken != null && rest.startsWith("/zd/") ->
                serveFolderZip(out, rest.removePrefix("/zd/"), address)
            grantToken != null && rest.startsWith("/u/") && method == "POST" ->
                receiveUpload(input, out, rest.removePrefix("/u/"), headers)
            grantToken != null -> respond(out, 404, "text/plain", "not found".toByteArray())

            path == "/" && method == "GET" -> serveLock(out, address, headers)
            path == "/api/info" -> json(out, infoJson())
            path == "/api/unlock" && method == "POST" -> unlock(input, out, headers, address)

            // The cookie-authorised API stays, because paired peers and the existing tests
            // speak it. A browser now takes the grant route instead.
            path == "/api/list" -> {
                if (!authorised(headers, address)) { unauthorised(out); return }
                json(out, listJson())
            }
            path.startsWith("/f/") -> {
                if (!authorised(headers, address)) { unauthorised(out); return }
                serveFile(out, path.removePrefix("/f/"), headers, address)
            }
            path == "/z" -> {
                if (!authorised(headers, address)) { unauthorised(out); return }
                serveZip(out, query, address)
            }
            path.startsWith("/u/") && method == "POST" -> {
                if (!authorised(headers, address)) { unauthorised(out); return }
                receiveUpload(input, out, path.removePrefix("/u/"), headers)
            }
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
        runCatching { out.flush() }
    }

    private fun touch(address: String, agent: String) {
        lastActivity.set(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        clients.compute(address) { _, existing ->
            existing?.also { it.lastSeen = now }
                ?: NearbyClient(address, shortAgent(agent), now, now)
        }
        onEvent(state())
    }

    /**
     * A session cookie, or a paired peer's token.
     *
     * Both are checked here so there is exactly one place that answers "may this request see
     * anything at all".
     */
    private fun authorised(headers: Map<String, String>, address: String): Boolean {
        if (!pinRequired) return true
        headers["x-filet-peer"]?.let { peer -> if (PairedPeers.isTrusted(context, peer)) return true }
        val cookie = headers["cookie"] ?: return false
        val session = cookie.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }?.removePrefix("$COOKIE=") ?: return false
        return sessions.containsKey("$address|$session")
    }

    private fun unlock(input: InputStream, out: OutputStream, headers: Map<String, String>, address: String) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceAtMost(1024))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val supplied = runCatching { JSONObject(String(body, 0, read)).optString("pin") }.getOrDefault("")

        val tries = attempts.computeIfAbsent(address) { AtomicInteger(0) }
        if (tries.get() >= MAX_ATTEMPTS) {
            respond(out, 429, "application/json", """{"error":"too many attempts"}""".toByteArray())
            return
        }
        if (supplied != pin) {
            tries.incrementAndGet()
            val left = (MAX_ATTEMPTS - tries.get()).coerceAtLeast(0)
            respond(out, 403, "application/json", """{"error":"wrong pin","left":$left}""".toByteArray())
            return
        }
        tries.set(0)
        val session = newSession()
        sessions["$address|$session"] = System.currentTimeMillis()

        // Mint the grant as well as the cookie. The cookie keeps the existing peer and API
        // behaviour working; the grant is the visible, revocable thing the user manages.
        val grant = grants.mint(shortAgent(headers["user-agent"] ?: ""), address)
        onEvent(state())

        respond(
            out, 200, "application/json",
            JSONObject().put("ok", true).put("go", "/a/${grant.token}/").toString().toByteArray(),
            extraHeaders = listOf("Set-Cookie: $COOKIE=$session; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    private fun unauthorised(out: OutputStream) =
        respond(out, 401, "application/json", """{"error":"locked"}""".toByteArray())

    /** The lock screen: the PIN box and nothing else. */
    private fun serveLock(out: OutputStream, address: String, headers: Map<String, String>) {
        // With the PIN off there is nothing to unlock, so mint a grant and go straight in -
        // otherwise the user is asked for a code that does not exist.
        if (!pinRequired) {
            val grant = grants.mint(shortAgent(headers["user-agent"] ?: ""), address)
            onEvent(state())
            redirect(out, "/a/${grant.token}/")
            return
        }
        respond(out, 200, "text/html; charset=utf-8", page("web/lock.html").toByteArray())
    }

    /** The browser, behind a grant. */
    private fun serveApp(out: OutputStream, grant: AccessGrant) {
        val body = page("web/app.html").replace("{{BASE}}", "/a/${grant.token}")
        respond(out, 200, "text/html; charset=utf-8", body.toByteArray())
    }

    /**
     * Load a page asset and fill in the things it cannot know.
     *
     * `{{THEME}}` is the app's own palette as CSS custom properties, so switching Filet to
     * Ember switches the web page to Ember too - the page is part of the app, not a separate
     * product with its own taste.
     */
    private fun page(asset: String): String {
        val raw = runCatching {
            context.assets.open(asset).use { it.readBytes() }
        }.getOrElse { FALLBACK_PAGE.toByteArray() }
        return String(raw)
            .replace("{{DEVICE}}", escapeHtml(deviceName()))
            .replace("{{HOST}}", escapeHtml(localAddress() ?: ""))
            .replace("{{UPLOADS}}", if (uploadsAllowed) "true" else "false")
            .replace("{{THEME}}", WebTheme.css(context))
    }

    private fun redirect(out: OutputStream, to: String) = respond(
        out, 303, "text/plain", ByteArray(0),
        extraHeaders = listOf("Location: $to"),
    )

    private fun infoJson(): String = JSONObject()
        .put("name", deviceName())
        .put("model", android.os.Build.MODEL)
        .put("app", "Filet")
        .put("uploads", uploadsAllowed)
        .toString()

    private fun dirTokenOf(query: String): String? = query.split('&')
        .firstOrNull { it.startsWith("d=") }
        ?.removePrefix("d=")
        ?.let { URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotEmpty() }

    /**
     * @param dir a directory token to list inside, or null for the share root.
     *
     * The trail comes back with the rows so the page can draw a breadcrumb without keeping
     * its own idea of where it is - the server is the one that knows what a token means.
     */
    private fun listJson(dir: String? = null): String {
        val rows = runBlocking { if (dir == null) shared.listing() else shared.listingIn(dir) }
            ?: return JSONObject().put("error", "gone").toString()
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("token", r.token)
                    .put("name", r.name)
                    .put("size", r.size)
                    .put("dir", r.isDir)
                    .put("mtime", r.node.mtime)
                    .put("linked", r.linked)
                    .put("ext", r.node.extension)
                    .put("thumb", hasThumb(r.node.extension))
            )
        }
        val trail = JSONArray()
        if (dir != null) {
            for ((token, name) in shared.trailTo(dir)) {
                trail.put(JSONObject().put("token", token).put("name", name))
            }
        }
        return JSONObject()
            .put("device", deviceName())
            .put("files", arr)
            .put("trail", trail)
            .put("at", dir ?: "")
            .toString()
    }

    private fun hasThumb(ext: String): Boolean = ext.lowercase() in THUMBABLE

    /**
     * A small preview image for a shared file.
     *
     * Downscaled here rather than serving the original inline: a folder of 12-megapixel photos
     * would otherwise push ~60 MB through the browser to draw twelve 64-pixel squares, over
     * Wi-Fi, on a phone's battery.
     */
    private fun serveThumb(out: OutputStream, token: String) {
        val path = shared.resolve(token)
        if (path == null) { respond(out, 404, "text/plain", "unknown token".toByteArray()); return }
        val bytes = Thumbnails.forWeb(context, vfs, path)
        if (bytes == null) { respond(out, 404, "text/plain", "no preview".toByteArray()); return }
        respond(
            out, 200, "image/jpeg", bytes,
            // Tokens are per-session, so a cached thumbnail can never outlive the share.
            extraHeaders = listOf("Cache-Control: private, max-age=3600"),
        )
    }

    /** A whole folder, streamed as a zip, with its tree preserved inside. */
    private fun serveFolderZip(out: OutputStream, token: String, address: String) {
        val root = shared.resolve(token)
        if (root == null || !shared.isReachable(root)) {
            respond(out, 404, "text/plain", "unknown token".toByteArray())
            return
        }
        val node = runCatching { runBlocking { vfs.stat(root) } }.getOrNull()
        if (node == null || !node.isDir) {
            respond(out, 404, "text/plain", "not a folder".toByteArray())
            return
        }

        writeStatus(
            out, 200, "application/zip",
            listOf(
                "Content-Disposition: attachment; filename=\"${zipSafe(node.name)}.zip\"",
                "Transfer-Encoding: chunked",
            ),
        )
        val chunked = ChunkedOutputStream(out)
        var count = 0
        runCatching {
            ZipOutputStream(chunked).use { zos ->
                fun walk(dir: VPath, prefix: String, depth: Int) {
                    if (depth > 24) return
                    val kids = runCatching { runBlocking { vfs.list(dir) } }.getOrElse { return }
                    for (kid in kids) {
                        if (kid.hidden) continue
                        val entry = prefix + kid.name
                        if (kid.isDir) {
                            walk(kid.path, "$entry/", depth + 1)
                        } else {
                            zos.putNextEntry(ZipEntry(entry))
                            runCatching { runBlocking { vfs.openRead(kid.path) }.use { it.copyTo(zos) } }
                            zos.closeEntry()
                            count++
                        }
                    }
                }
                walk(root, "", 0)
            }
        }
        chunked.finish()
        clients[address]?.state = "downloaded ${node.name} ($count files)"
        onEvent(state())
    }

    private fun zipSafe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "folder" }

    /**
     * Serve one shared file, with `Range` support.
     *
     * Range is what makes a transfer resumable across a dropped connection and what lets a
     * browser seek in a video without downloading it first. It is three lines of arithmetic
     * and it is the entire reason this is HTTP rather than a bespoke protocol.
     */
    private fun serveFile(out: OutputStream, token: String, headers: Map<String, String>, address: String) {
        val path = shared.resolve(token)
        if (path == null) { respond(out, 404, "text/plain", "unknown token".toByteArray()); return }
        val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull()
        if (node == null) {
            // The source went away between listing and fetch. 410 is the honest answer.
            respond(out, 410, "text/plain", "no longer available".toByteArray())
            return
        }

        val total = node.size
        val range = headers["range"]?.removePrefix("bytes=")?.split('-')
        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        var partial = false
        if (range != null && range.isNotEmpty() && total > 0) {
            start = range[0].toLongOrNull() ?: 0L
            if (range.size > 1 && range[1].isNotEmpty()) end = range[1].toLongOrNull() ?: end
            partial = true
        }
        val length = if (total > 0) (end - start + 1).coerceAtLeast(0) else -1L

        val extra = ArrayList<String>()
        extra += "Accept-Ranges: bytes"
        extra += "Content-Disposition: attachment; filename=\"${node.name.replace('"', '_')}\""
        if (partial && total > 0) extra += "Content-Range: bytes $start-$end/$total"
        if (length >= 0) extra += "Content-Length: $length"

        writeStatus(out, if (partial) 206 else 200, guessMime(node.name), extra)

        runCatching {
            runBlocking { vfs.openRead(path) }.use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip(start - skipped)
                    if (n <= 0) break
                    skipped += n
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
        clients[address]?.let { it.downloads++; it.state = "downloaded ${it.downloads} file(s)" }
        lastActivity.set(System.currentTimeMillis())
        onEvent(state())
    }

    /**
     * Several files as one zip, **streamed**.
     *
     * Never buffered to disk: a 4 GB selection must not require 4 GB free on the phone. That
     * means no Content-Length, which is exactly what chunked transfer encoding is for.
     */
    private fun serveZip(out: OutputStream, query: String, address: String) {
        val tokens = query.substringAfter("tokens=", "").split(',')
            .map { URLDecoder.decode(it, "UTF-8") }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) { respond(out, 400, "text/plain", "no tokens".toByteArray()); return }

        writeStatus(
            out, 200, "application/zip",
            listOf(
                "Content-Disposition: attachment; filename=\"filet-${System.currentTimeMillis()}.zip\"",
                "Transfer-Encoding: chunked",
            ),
        )
        val chunked = ChunkedOutputStream(out)
        runCatching {
            ZipOutputStream(chunked).use { zos ->
                for (token in tokens) {
                    val path = shared.resolve(token) ?: continue
                    val node = runCatching { runBlocking { vfs.stat(path) } }.getOrNull() ?: continue
                    if (node.isDir) continue
                    zos.putNextEntry(ZipEntry(node.name))
                    runBlocking { vfs.openRead(path) }.use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        chunked.finish()
        clients[address]?.state = "downloaded a zip"
        onEvent(state())
    }

    /**
     * A file arriving from a browser.
     *
     * Off by default, and what arrives goes to **quarantine** with a distinct name, never into
     * the shared set and never over an existing file. "Send a file from my laptop to my phone
     * with nothing installed on the laptop" is worth having; silently overwriting is not.
     */
    private fun receiveUpload(
        input: InputStream,
        out: OutputStream,
        rawName: String,
        headers: Map<String, String>,
    ) {
        if (!uploadsAllowed) {
            respond(out, 403, "application/json", """{"error":"uploads are off"}""".toByteArray())
            return
        }
        val name = URLDecoder.decode(rawName, "UTF-8").substringAfterLast('/').ifEmpty { "upload" }
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        runCatching {
            runBlocking {
                shared.ensureFolders()
                var target = shared.quarantine.child(sanitise(name))
                var i = 2
                while (vfs.stat(target) != null && i < 999) {
                    val stem = sanitise(name).substringBeforeLast('.')
                    val ext = sanitise(name).substringAfterLast('.', "")
                    target = shared.quarantine.child(if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext")
                    i++
                }
                vfs.openWrite(target).use { o ->
                    val buf = ByteArray(64 * 1024)
                    var remaining = if (length >= 0) length else Long.MAX_VALUE
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        remaining -= n
                    }
                }
                target
            }
        }.onSuccess {
            respond(out, 200, "application/json", """{"ok":true,"name":"${it.name}"}""".toByteArray())
        }.onFailure {
            respond(out, 500, "application/json", """{"error":"could not write"}""".toByteArray())
        }
        lastActivity.set(System.currentTimeMillis())
    }

    /** Names from the network are never trusted: no separators, no traversal, no control bytes. */
    private fun sanitise(name: String): String =
        name.replace(Regex("[/\\\\\\u0000-\\u001f]"), "_").trim().ifEmpty { "upload" }.take(120)

    // ────────────────────────── plumbing ──────────────────────────

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, contentType: String, extra: List<String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        for (h in extra) sb.append(h).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList(),
    ) {
        writeStatus(out, code, contentType, extraHeaders + "Content-Length: ${body.size}")
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, body: String) =
        respond(out, 200, "application/json; charset=utf-8", body.toByteArray())

    private fun newSession(): String {
        val b = ByteArray(18)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; 429 -> "Too Many Requests"
        else -> "Error"
    }

    private fun shortAgent(ua: String): String = when {
        ua.contains("Firefox") -> "Firefox"
        ua.contains("Edg/") -> "Edge"
        ua.contains("Chrome") -> "Chrome"
        ua.contains("Safari") -> "Safari"
        ua.contains("Filet") -> "Filet"
        ua.isEmpty() -> "unknown"
        else -> ua.take(24)
    } + when {
        ua.contains("Windows") -> " / Windows"
        ua.contains("Macintosh") -> " / Mac"
        ua.contains("iPhone") -> " / iPhone"
        ua.contains("Android") -> " / Android"
        ua.contains("Linux") -> " / Linux"
        else -> ""
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "pdf" -> "application/pdf"; "txt", "log", "md" -> "text/plain"
        "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    companion object {
        const val DEFAULT_PORT = 8321
        private const val COOKIE = "filet_session"
        private const val MAX_ATTEMPTS = 5

        /**
         * The device's LAN address.
         *
         * Site-local IPv4 only: the URL has to be typed or scanned by a person, and an IPv6
         * link-local address with a scope suffix is not that.
         */
        /** Superseded by [NetAddresses]; kept as the single-address shorthand it always was. */
        fun localAddress(): String? = NetAddresses.primary()?.ip

        /** Extensions worth asking for a preview of. */
        private val THUMBABLE = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
            "mp4", "mkv", "webm", "3gp", "mov", "m4v", "avi",
            "apk",
        )

        private const val FALLBACK_PAGE = """<!doctype html><meta charset=utf-8>
<title>Filet</title><body style="font:15px system-ui;padding:24px">
<h1>Filet</h1><p>The web page asset is missing from this build.</p></body>"""
    }
}

/**
 * Chunked transfer encoding, by hand.
 *
 * Needed because the zip endpoint cannot know its length in advance and must not buffer to
 * find out. Every write becomes one chunk; [finish] writes the terminating zero-length chunk.
 */
private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        out.write("${len.toString(16)}\r\n".toByteArray())
        out.write(b, off, len)
        out.write("\r\n".toByteArray())
    }

    override fun flush() = out.flush()

    fun finish() {
        runCatching {
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }
    }
}
