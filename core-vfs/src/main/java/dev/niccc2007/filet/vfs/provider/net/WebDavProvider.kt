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
    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR,
    )

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        connections.all().filter { it.protocol == NetProtocol.WEBDAV }
            .map { VNode(netPath(scheme, it.id, it.share.ifEmpty { "/" }), true, -1L, 0L) }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        val body = request(c, remote, "PROPFIND", depth = "1", body = PROPFIND_BODY, path = path)
        parseMultiStatus(body, path, remote)
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        runCatching {
            val body = request(c, remote, "PROPFIND", depth = "0", body = PROPFIND_BODY, path = path)
            parseMultiStatus(body, path.parent ?: path, remote.trimEnd('/').substringBeforeLast('/', ""))
                .firstOrNull { it.name == path.name }
                ?: VNode(path, isDir = true, size = -1L, mtime = 0L)
        }.getOrNull()
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        val http = open(c, remote, "GET", path)
        if (http.responseCode !in 200..299) {
            http.disconnect()
            throw status(http.responseCode, path)
        }
        object : InputStream() {
            private val src = http.inputStream
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); http.disconnect() }
        }
    }

    /**
     * `PUT` needs the whole body, and `HttpURLConnection` cannot stream without a known
     * length unless chunked encoding is used - which several WebDAV servers reject. So the
     * write is buffered and sent on close, and the size cap is stated rather than discovered.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        if (append) throw VfsException.Unsupported("WebDAV has no append")
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val bytes = toByteArray()
                val http = open(c, remote, "PUT", path)
                http.doOutput = true
                http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) }
                val code = http.responseCode
                http.disconnect()
                if (code !in 200..299) throw status(code, path)
            }
        }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        val http = open(c, remote, if (isDir) "MKCOL" else "PUT", path)
        if (!isDir) {
            http.doOutput = true
            http.setFixedLengthStreamingMode(0)
            http.outputStream.use { }
        }
        val code = http.responseCode
        http.disconnect()
        if (code !in 200..299) throw status(code, path)
        VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        val http = open(c, remote, "DELETE", path)
        val code = http.responseCode
        http.disconnect()
        if (code !in 200..299 && code != 404) throw status(code, path)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val c = conn(id, path)
        val parent = remote.trimEnd('/').substringBeforeLast('/', "")
        val http = open(c, remote, "MOVE", path)
        http.setRequestProperty("Destination", urlFor(c, "$parent/$newName"))
        http.setRequestProperty("Overwrite", "F")
        val code = http.responseCode
        http.disconnect()
        if (code !in 200..299) throw status(code, path)
        VNode(path.parent!!.child(newName), false, -1L, System.currentTimeMillis())
    }

    // ────────────────────────── http ──────────────────────────

    private fun conn(id: String, path: VPath): NetConnection =
        connections.byId(id) ?: throw VfsException.NotFound(path)

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

    private fun open(c: NetConnection, remote: String, method: String, path: VPath): HttpURLConnection =
        runCatching {
            val http = URL(urlFor(c, remote)).openConnection() as HttpURLConnection
            setMethod(http, method)
            http.connectTimeout = 15_000
            http.readTimeout = 30_000
            http.instanceFollowRedirects = true
            if (!c.anonymous && c.user.isNotEmpty()) {
                val token = Base64.encodeToString("${c.user}:${c.password}".toByteArray(), Base64.NO_WRAP)
                http.setRequestProperty("Authorization", "Basic $token")
            }
            http.setRequestProperty("User-Agent", "Filet")
            http
        }.getOrElse { throw VfsException.Io(path, it) }

    private fun request(
        c: NetConnection,
        remote: String,
        method: String,
        depth: String?,
        body: String?,
        path: VPath,
    ): String {
        val http = open(c, remote, method, path)
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
            throw status(code, path)
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

    private fun status(code: Int, path: VPath): VfsException = when (code) {
        401, 403 -> VfsException.AccessDenied(path)
        404 -> VfsException.NotFound(path)
        405, 409 -> VfsException.AlreadyExists(path)
        else -> VfsException.Io(path, IllegalStateException("HTTP $code"))
    }

    private companion object {
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
