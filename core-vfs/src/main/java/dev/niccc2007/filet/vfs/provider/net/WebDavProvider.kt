package dev.niccc2007.filet.vfs.provider.net

import android.util.Base64
import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * WebDAV, written directly against `HttpURLConnection`.
 *
 * No library, on purpose. Every maintained Android WebDAV client is a wrapper around an HTTP
 * stack plus about two hundred lines of XML parsing, and WebDAV itself is five verbs
 * (`PROPFIND`, `GET`, `PUT`, `MKCOL`, `DELETE`, `MOVE`). Pulling in a transitive HTTP client
 * and its dependency tree to avoid writing those is a bad trade for an app that cares about
 * its own size.
 *
 * Addressing: `dav:///<connectionId>/remote.php/dav/files/you/notes.txt`.
 */
class WebDavProvider(private val connections: NetConnections) : FileSystemProvider {

    override val scheme: String = NetProtocol.WEBDAV.scheme
    override val remote: Boolean = true

    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR,
    )

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        connections.all().filter { it.protocol == NetProtocol.WEBDAV }
            .map { VNode(netPath(scheme, it.id, it.share.ifEmpty { "/" }), true, -1L, 0L) }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        onAddress(id, path) { c ->
            val body = request(c, remote, "PROPFIND", depth = "1", body = PROPFIND_BODY, path = path)
            parseMultiStatus(body, path, remote)
        }
    }

    /**
     * Free and total bytes, from RFC 4331.
     *
     * Without this a mounted share reported no size at all and every surface drew it as "size
     * unknown" - including the storage card, which then had no bar and sat visibly shorter than
     * the local volumes beside it.
     *
     * Cached briefly. It is asked for once per card draw and it is a network round trip, so a
     * scrolling list would otherwise make one per frame.
     */
    private suspend fun quota(path: VPath): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        val key = splitNetPath(path).first
        quotaCache[key]?.let { (cached, at) ->
            // A hit and a miss do not keep for the same length of time. A real figure is stable
            // for a while; a null means "not answering right now", and that stops being true the
            // moment the other device wakes up. Caching both for 30s meant a share coming back
            // online still read as offline for half a minute after it returned.
            val ttl = if (cached == null) QUOTA_MISS_TTL_MS else QUOTA_TTL_MS
            if (now - at < ttl) return cached
        }

        val got = runCatching {
            val (id, remote) = splitNetPath(path)
            onAddress(id, path) { c ->
                val body = request(
                    c, remote, "PROPFIND", depth = "0", body = PROPFIND_BODY, path = path, quick = true,
                )
                val avail = tagValue(body, "quota-available-bytes")?.toLongOrNull()
                val used = tagValue(body, "quota-used-bytes")?.toLongOrNull()
                if (avail == null || used == null) null else avail to used
            }
        }.getOrNull()

        // A null is cached too. A server with no quota support would otherwise be asked on every
        // single draw, forever, for an answer it is never going to give.
        quotaCache[key] = got to now
        return got
    }

    /** The first value of a DAV property, namespace prefix ignored. */
    private fun tagValue(xml: String, local: String): String? {
        val open = Regex("<[A-Za-z0-9]*:?" + local + "\\s*>")
        val m = open.find(xml) ?: return null
        val start = m.range.last + 1
        val end = xml.indexOf('<', start)
        if (end < 0) return null
        return xml.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    override suspend fun freeSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        quota(path)?.first
    }

    override suspend fun totalSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        // RFC 4331 gives free and used, not total. Their sum is the volume, which is what a
        // reader means by "of 224 GB".
        quota(path)?.let { (avail, used) -> avail + used }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        runCatching {
            onAddress(id, path) { c ->
                val body = request(c, remote, "PROPFIND", depth = "0", body = PROPFIND_BODY, path = path)
                parseMultiStatus(body, path.parent ?: path, remote.trimEnd('/').substringBeforeLast('/', ""))
                    .firstOrNull { it.name == path.name }
                    ?: VNode(path, isDir = true, size = -1L, mtime = 0L)
            }
        }.getOrNull()
    }

    /**
     * Whether the server will serve part of a file.
     *
     * Cached per connection: it is a property of the server, not of the file, and asking once
     * per read would put a HEAD in front of every range.
     *
     * An absent or `none` header is taken as NO. A server that ignores `Range` answers 200 with
     * the whole body, and a caller that believed it was getting bytes 4000-4100 would read the
     * first hundred bytes of the file and call them the central directory.
     */
    private val rangeable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override suspend fun supportsRanges(path: VPath): Boolean = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        rangeable[id]?.let { return@withContext it }
        val answer = runCatching {
            onAddress(id, path) { c ->
                val http = open(c, remote, "HEAD", path, quick = true)
                val ok = http.getHeaderField("Accept-Ranges")
                    ?.trim()?.lowercase()?.let { it.isNotEmpty() && it != "none" } == true
                http.disconnect()
                    ok
            }
        }
        // Only a real answer is remembered. Caching the failure would switch ranges off for the
        // rest of the session because the share happened to be asleep when it was first asked.
        answer.getOrNull()?.let { rangeable[id] = it }
        answer.getOrDefault(false)
    }

    /**
     * Bytes [from] until [until], inclusive of the first and exclusive of the last.
     *
     * The whole point of the net providers supporting this: an archive reads its index from the
     * END of the file and then seeks to each entry, so a forward-only stream cannot open one at
     * all. With ranges, listing an archive of any size is a handful of small requests.
     *
     * Verified rather than trusted. A server that ignores the header answers **200** with the
     * entire body; only **206** means it honoured the range, and anything else is refused here
     * rather than handed back as if it were the slice that was asked for.
     */
    override suspend fun readRange(path: VPath, from: Long, until: Long): ByteArray =
        withContext(Dispatchers.IO) {
            require(from >= 0 && until > from) { "bad range $from..$until" }
            val (id, remote) = splitNetPath(path)
            onAddress(id, path) { c ->
                val http = open(c, remote, "GET", path)
                http.setRequestProperty("Range", "bytes=$from-${until - 1}")
                val code = http.responseCode
                if (code != 206) {
                    http.disconnect()
                    throw VfsException.Unsupported(
                        if (code == 200) "this server ignores Range" else "range refused: HTTP $code",
                    )
                }
                    val out = http.inputStream.use { it.readBytes() }
                http.disconnect()
                out
            }
        }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        onAddress(id, path) { c ->
            val http = open(c, remote, "GET", path)
            if (http.responseCode !in 200..299) {
                http.disconnect()
                throw status(http.responseCode, path, "GET")
            }
            object : InputStream() {
                private val src = http.inputStream
                override fun read() = src.read()
                override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
                override fun available() = src.available()
                override fun close() { src.close(); http.disconnect() }
            }
        }
    }

    /**
     * `PUT` needs the whole body, and `HttpURLConnection` cannot stream without a known
     * length unless chunked encoding is used - which several WebDAV servers reject. So the
     * write is buffered and sent on close, and the size cap is stated rather than discovered.
     */
    /**
     * `PUT`, spilled to a temporary file rather than held in the heap.
     *
     * This used to be a `ByteArrayOutputStream`: the whole file was accumulated in memory and
     * sent on close. Three things were wrong with it, and together they are why a copy over a
     * mounted share crawled.
     *
     *  1. **Nothing left the device until the last byte arrived.** Reading and sending were
     *     strictly sequential, so the wall time was read-time plus send-time rather than the
     *     larger of the two.
     *  2. **The array doubled as it grew.** A 60 MB APK is copied through roughly 5, 10, 20, 40
     *     and 80 MB arrays on the way, which is a great deal of allocation and GC for bytes that
     *     are only passing through.
     *  3. **It held the whole file twice** at the moment `toByteArray` ran - once in the stream's
     *     buffer and once in the copy - which on a phone is where a large file starts trimming
     *     other apps out of memory.
     *
     * A temp file has a known length, so the request still carries a real `Content-Length` and
     * nothing has to rely on chunked encoding, which several WebDAV servers reject. The file is
     * streamed out through a buffer and deleted afterwards, on failure as well as success.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        if (append) throw VfsException.Unsupported("WebDAV has no append")
        val (id, remote) = splitNetPath(path)

        val spill = java.io.File.createTempFile("filet-put-", ".tmp")
        val sink = java.io.BufferedOutputStream(java.io.FileOutputStream(spill), UPLOAD_BUFFER)

        object : OutputStream() {
            private var closed = false

            override fun write(b: Int) = sink.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
            override fun flush() = sink.flush()

            override fun close() {
                if (closed) return
                closed = true
                try {
                    sink.close()
                    // The address is resolved HERE rather than when the stream was handed out,
                    // and the body is on disk rather than in a stream that has already been
                    // consumed - so an address that died between opening and closing costs a
                    // rotation and a re-send, not a failed copy.
                    onAddress(id, path) { c ->
                        val http = open(c, remote, "PUT", path)
                        http.doOutput = true
                        http.setFixedLengthStreamingMode(spill.length())
                        java.io.BufferedOutputStream(http.outputStream, UPLOAD_BUFFER).use { out ->
                            java.io.FileInputStream(spill).use { src ->
                                val buf = ByteArray(UPLOAD_BUFFER)
                                while (true) {
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        val code = http.responseCode
                        http.disconnect()
                                    if (code !in 200..299) throw status(code, path, "PUT")
                    }
                } finally {
                    // On the failure path too. A temp file left behind per failed upload fills
                    // the cache directory quietly.
                    spill.delete()
                }
            }
        }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        onAddress(id, path) { c ->
            val http = open(c, remote, if (isDir) "MKCOL" else "PUT", path)
            if (!isDir) {
                http.doOutput = true
                http.setFixedLengthStreamingMode(0)
                http.outputStream.use { }
            }
            val code = http.responseCode
            http.disconnect()
            if (code !in 200..299) throw status(code, path, if (isDir) "MKCOL" else "PUT")
            VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
        }
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        onAddress(id, path) { c ->
            val http = open(c, remote, "DELETE", path)
            val code = http.responseCode
            http.disconnect()
            if (code !in 200..299 && code != 404) throw status(code, path, "DELETE")
        }
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val parent = remote.trimEnd('/').substringBeforeLast('/', "")
        onAddress(id, path) { c ->
            val http = open(c, remote, "MOVE", path)
            http.setRequestProperty("Destination", urlFor(c, "$parent/$newName"))
            http.setRequestProperty("Overwrite", "F")
            val code = http.responseCode
            http.disconnect()
            if (code !in 200..299) throw status(code, path, "MOVE")
            VNode(path.parent!!.child(newName), false, -1L, System.currentTimeMillis())
        }
    }

    // ────────────────────────── http ──────────────────────────

    /**
     * Run one operation against this remote, trying its other addresses if nothing answers.
     *
     * ## The bug this fixes
     *
     * Rotation used to live inside the helper that BUILDS the connection - and that helper does
     * no network at all. `URL.openConnection()` constructs an object; the TCP connect happens
     * later, at `responseCode` or the first read, in each caller. So the branch that moved to the
     * next address could only fire on a malformed URL, every dead address threw straight out of
     * the caller untouched, and a remote with three saved addresses only ever tried the first
     * one. Adding a second address appeared to do nothing, because it did nothing.
     * `WhereConnectHappensTest` pins the premise so it cannot be moved back.
     *
     * The fix is to put it where the failure actually happens: around the whole operation,
     * response included. [NetDial] is that, shared with the other three protocols, which had the
     * same field and did not read it at all.
     */
    private inline fun <T> onAddress(id: String, path: VPath, block: (NetConnection) -> T): T {
        val c = connections.byId(id) ?: throw VfsException.NotFound(path)
        return NetDial.over(
            hosts = connections.candidates(c),
            path = path,
            onGood = { h -> connections.noteGood(id, h) },
        ) { h -> block(c.copy(host = h)) }
    }

    private fun urlFor(c: NetConnection, remote: String): String {
        val scheme = if (c.useTls) "https" else "http"
        val port = when {
            c.port <= 0 -> ""
            c.useTls && c.port == 443 -> ""
            !c.useTls && c.port == 80 -> ""
            else -> ":${c.port}"
        }
        val encoded = remote.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$scheme://${c.host}$port/$encoded"
    }

    /**
     * Set a method the platform's HTTP stack does not believe in.
     *
     * `HttpURLConnection.setRequestMethod` validates against a fixed list - OPTIONS, GET,
     * HEAD, POST, PUT, DELETE, TRACE, PATCH - so `PROPFIND`, `MKCOL` and `MOVE` throw
     * `ProtocolException` and WebDAV is impossible through the public API alone. The verb
     * itself is fine: Android's stack is OkHttp underneath, and OkHttp's own
     * `HttpMethod.permitsRequestBody` already names PROPFIND, MKCOL and LOCK. Only the
     * setter's whitelist is in the way.
     *
     * So the verb is written straight into `method` - a **protected field of the public SDK
     * class** `java.net.HttpURLConnection`, not a hidden interface - which is the same route
     * every WebDAV client on Android takes. If a future stack moves that field, this throws a
     * plain "unsupported" rather than silently sending the wrong verb.
     */
    private fun setMethod(http: HttpURLConnection, method: String) {
        if (runCatching { http.requestMethod = method }.isSuccess) return
        var klass: Class<*>? = http.javaClass
        while (klass != null) {
            val field = runCatching { klass!!.getDeclaredField("method") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                field.set(http, method)
                return
            }
            klass = klass.superclass
        }
        throw VfsException.Unsupported("This device's HTTP stack refuses the $method method.")
    }

    private fun open(
        c: NetConnection,
        remote: String,
        method: String,
        path: VPath,
        /**
         * Short timeouts, for a request whose only job is to find out whether anything is there.
         *
         * 15 seconds is right for reading a file and wrong for a liveness check: the card polls,
         * and a poll that waits fifteen seconds to fail means a share that has been switched off
         * keeps reading as online for most of a minute. A device on the same Wi-Fi answers in
         * milliseconds or it is not going to.
         */
        quick: Boolean = false,
    ): HttpURLConnection =
        runCatching {
            val http = URL(urlFor(c, remote)).openConnection() as HttpURLConnection
            setMethod(http, method)
            http.connectTimeout = if (quick) PROBE_TIMEOUT_MS else 15_000
            http.readTimeout = if (quick) PROBE_TIMEOUT_MS else 30_000
            http.instanceFollowRedirects = true
            if (!c.anonymous && c.user.isNotEmpty()) {
                val token = Base64.encodeToString("${c.user}:${c.password}".toByteArray(), Base64.NO_WRAP)
                http.setRequestProperty("Authorization", "Basic $token")
            }
            http.setRequestProperty("User-Agent", "Filet")
            http
        }.getOrElse {
            // Nothing has been sent at this point - this is a malformed URL or a stack that
            // refuses the verb, not a network failure. Address rotation is NOT done here, and
            // that is the correction: it used to be, on the assumption that this is where a
            // connection is made. It is not, so it fired on nothing. See [onAddress].
            throw VfsException.Io(path, it)
        }

    private fun request(
        c: NetConnection,
        remote: String,
        method: String,
        depth: String?,
        body: String?,
        path: VPath,
        quick: Boolean = false,
    ): String {
        val http = open(c, remote, method, path, quick = quick)
        depth?.let { http.setRequestProperty("Depth", it) }
        if (body != null) {
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) }
        }
        val code = http.responseCode
        if (code !in 200..299) {
            http.disconnect()
            throw status(code, path, method)
        }
        val text = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        return text
    }

    /**
     * Parse a `207 Multi-Status` body.
     *
     * Namespace-agnostic on purpose: servers disagree about prefixes (`d:`, `D:`, none), and
     * matching on the local name is what makes the same code work against Nextcloud, IIS and
     * Apache mod_dav without a per-server quirk table.
     */
    private fun parseMultiStatus(xml: String, parent: VPath, parentRemote: String): List<VNode> {
        val out = ArrayList<VNode>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var href: String? = null
        var isDir = false
        var size = -1L
        var mtime = 0L
        var inProp = false
        var current: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    current = parser.name.substringAfterLast(':').lowercase()
                    when (current) {
                        "response" -> { href = null; isDir = false; size = -1L; mtime = 0L }
                        "prop" -> inProp = true
                        "collection" -> if (inProp) isDir = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (current) {
                        "href" -> href = text
                        "getcontentlength" -> size = text.toLongOrNull() ?: -1L
                        "getlastmodified" -> mtime = parseHttpDate(text)
                        "resourcetype" -> Unit
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfterLast(':').lowercase()
                    if (name == "prop") inProp = false
                    if (name == "response") {
                        val decoded = href?.let { URLDecoder.decode(it, "UTF-8") }
                        val fileName = decoded?.trimEnd('/')?.substringAfterLast('/')
                        // The first response in a Depth:1 body is the collection itself.
                        if (!fileName.isNullOrEmpty() && !samePath(decoded, parentRemote)) {
                            out += VNode(
                                path = parent.child(fileName),
                                isDir = isDir,
                                size = if (isDir) -1L else size,
                                mtime = mtime,
                                hidden = fileName.startsWith("."),
                            )
                        }
                    }
                    current = null
                }
            }
            parser.next()
        }
        return out
    }

    /**
     * Is this `<href>` the collection that was asked for, rather than one of its children?
     *
     * A `Depth: 1` body always begins with the collection itself, and it must not appear
     * inside its own listing. Compared on the decoded path alone, because servers return
     * hrefs as absolute paths, as full URLs, and with or without a trailing slash - any of
     * which can name the same resource.
     *
     * The earlier version also accepted `href.endsWith(remote)`, which is true of *every*
     * href when `remote` is empty - so listing the root of a share returned nothing at all.
     */
    private fun samePath(href: String, remote: String): Boolean {
        val h = href.substringAfter("://").substringAfter('/', "").trim('/')
        return h == remote.trim('/')
    }

    private fun parseHttpDate(text: String): Long = runCatching {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        fmt.parse(text)?.time ?: 0L
    }.getOrDefault(0L)

    /**
     * An HTTP status as the failure the caller can act on.
     *
     * [method] is required because 405 means two unrelated things. RFC 4918 has MKCOL answer it
     * when the collection is already there - but a share with writing switched off answers it to
     * PUT, DELETE and MOVE as the plain HTTP "this method is not allowed here". Reading it as
     * "already exists" for every verb told somebody trying to save a file that their file was
     * already there, which is both wrong and the opposite of actionable.
     */
    private fun status(code: Int, path: VPath, method: String): VfsException = when (code) {
        401 -> VfsException.AccessDenied(path, unauthenticated = true)
        403 -> VfsException.AccessDenied(path)
        404 -> VfsException.NotFound(path)
        405 -> if (method == "MKCOL") VfsException.AlreadyExists(path) else VfsException.AccessDenied(path)
        409 -> VfsException.AlreadyExists(path)
        else -> VfsException.Io(path, IllegalStateException("HTTP $code"))
    }

    private val quotaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>?, Long>>()

    private companion object {
        /** How long a free-space reading is reused. It is a network round trip per card draw. */
        const val QUOTA_TTL_MS = 30_000L

        /**
         * How long an unreachable answer keeps.
         *
         * Short. It is a statement about this moment, not about the share.
         */
        const val QUOTA_MISS_TTL_MS = 4_000L

        /** How long a liveness probe waits before deciding nothing is there. */
        const val PROBE_TIMEOUT_MS = 2_500

        /**
         * Bytes moved per read and per write on an upload.
         *
         * 64 KB rather than the 8 KB default: on Wi-Fi the per-call overhead dominates below
         * about this size, and the difference over a large file is measurable rather than
         * theoretical.
         */
        const val UPLOAD_BUFFER = 64 * 1024

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:quota-available-bytes/>
                <d:quota-used-bytes/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
