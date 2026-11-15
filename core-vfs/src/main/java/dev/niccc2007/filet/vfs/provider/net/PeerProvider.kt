package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.RangedInput
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the app must tell the provider about a peer for it to be reachable. */
data class PeerEndpoint(
    val uuid: String,
    val label: String,
    val host: String,
    val port: Int,
    /** The pairing token, sent as a header. Absent means "not paired yet". */
    val token: String?,
)

/**
 * **A peer is not a transfer target. A peer is a volume.**
 *
 * Browsing another phone's shared set is identical to browsing an SD card: same pane, same
 * rows, same drag and drop. Sending a file is then just *moving* it, and the whole feature
 * falls out of the VFS that already exists (NEARBY.md, opening thesis).
 *
 * Addressing: `peer:///<peerUuid>/<name>/<name>...` - the names the other phone lists, so a row,
 * a viewer's title, a file's kind and the name a copy lands under are all the real name. The
 * other phone serves by **token** and has no path-shaped endpoint at all, so each name is turned
 * back into the token it was listed with ([tokens]) before anything is fetched; nothing outside
 * the other device's shared set is expressible from here.
 *
 * Bug identified here: rows used to be addressed by token, `peer:///<uuid>/5fb6e7b70d...`, with
 * the name kept in a side table for the row renderer to look up. Nothing looked it up once the
 * rows were redrawn, so another phone's files listed, opened and copied under their tokens - and
 * with no extension, a file's kind was guessed from its bytes or not at all.
 *
 * A shared folder opens like any folder: its listing is asked for with the folder's own token,
 * which the other phone minted when it listed the folder.
 */
class PeerProvider(
    private val endpoints: () -> List<PeerEndpoint>,
) : FileSystemProvider {

    override val scheme: String = SCHEME
    override val remote: Boolean = true

    override val capabilities: Set<Capability> = setOf(Capability.READ, Capability.WRITE)

    /** The token each listed row path was listed with, by the path's string. */
    private val tokens = HashMap<String, String>()

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        endpoints().map { e ->
            VNode(VPath.of(SCHEME, "/${e.uuid}"), isDir = true, size = -1L, mtime = 0L, writable = false)
        }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val segs = path.segments
        if (segs.isEmpty()) return@withContext roots()
        val e = endpointFor(segs[0], path)
        val dir = if (segs.size > 1) tokenOf(path) else null
        val target = if (dir == null) "/api/list" else "/api/list?d=${URLEncoder.encode(dir, "UTF-8")}"
        val obj = runCatching { JSONObject(get(e, target, path)) }.getOrElse { throw VfsException.Io(path, it) }
        if (obj.has("error")) throw VfsException.NotFound(path)
        // A Filet from before shared folders could be opened answers any listing with its share's
        // top level, and gives itself away by not naming the folder it was asked for.
        if (dir != null && obj.optString("at") != dir) {
            throw VfsException.Unsupported("Update Filet on ${e.label} to open its folders")
        }
        val files = obj.optJSONArray("files") ?: return@withContext emptyList()
        val rows = (0 until files.length()).map { files.getJSONObject(it) }
        val names = PeerNames.unique(rows.map { PeerNames.clean(it.optString("name"), it.getString("token")) })
        synchronized(tokens) {
            rows.forEachIndexed { i, f -> tokens[path.child(names[i]).toString()] = f.getString("token") }
        }
        rows.mapIndexed { i, f ->
            VNode(
                path = path.child(names[i]),
                isDir = f.optBoolean("dir"),
                size = f.optLong("size", -1L),
                mtime = f.optLong("mtime"),
                writable = false,
            )
        }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val segs = path.segments
        if (segs.isEmpty()) return@withContext null
        if (segs.size == 1) {
            return@withContext VNode(path, isDir = true, size = -1L, mtime = 0L, writable = false)
        }
        runCatching { list(path.parent!!).firstOrNull { it.path == path } }.getOrNull()
    }

    override suspend fun openRead(path: VPath): InputStream = openReadAt(path, 0L)

    /** Filet's own server answers ranges (see `nearby/HttpServer`). */
    override suspend fun supportsRanges(path: VPath): Boolean = true

    override suspend fun readRange(path: VPath, from: Long, until: Long): ByteArray {
        require(from >= 0 && until > from) { "bad range $from..$until" }
        return openReadAt(path, from).use { RangedInput.readExactly(it, (until - from).toInt()) }
    }

    /** The file from [offset]; as for WebDAV, a whole-file answer is skipped into rather than trusted. */
    override suspend fun openReadAt(path: VPath, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val segs = path.segments
        if (segs.size < 2) throw VfsException.IsADirectory(path)
        val e = endpointFor(segs[0], path)
        var http = fetch(e, path, offset)
        var code = http.responseCode
        if (code == 404) {
            // The other phone's share restarted since this folder was listed, and the tokens it
            // handed out went with it. Listed again, the name finds its new token.
            http.disconnect()
            forget(path)
            http = fetch(e, path, offset)
            code = http.responseCode
        }
        if (code == 404 || code == 410) { http.disconnect(); throw VfsException.NotFound(path) }
        if (code !in 200..299) { http.disconnect(); throw VfsException.Io(path, IllegalStateException("HTTP $code")) }
        val stream = object : InputStream() {
            private val src = http.inputStream
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); http.disconnect() }
        }
        if (offset > 0 && code != 206) RangedInput.skipFully(stream, offset)
        stream
    }

    private suspend fun fetch(e: PeerEndpoint, path: VPath, offset: Long): HttpURLConnection {
        val http = connect(e, "/f/${URLEncoder.encode(tokenOf(path), "UTF-8")}", "GET", path)
        if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
        return http
    }

    /**
     * Writing to a peer means uploading into its quarantine folder, and only if it allows it.
     *
     * The name has to be known up front, so the VPath's own name carries it - which is why a
     * copy *to* a peer targets `peer:///<uuid>/<filename>`. Only the top level takes one: an
     * upload always lands in the other phone's quarantine, whichever folder was showing.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        if (append) throw VfsException.Unsupported("a peer cannot append")
        val segs = path.segments
        if (segs.size != 2) throw VfsException.Unsupported("a file is sent to another phone's top level")
        val e = endpointFor(segs[0], path)
        val name = URLEncoder.encode(path.name, "UTF-8")
        object : java.io.ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val bytes = toByteArray()
                val http = connect(e, "/u/$name", "POST", path)
                http.doOutput = true
                http.setFixedLengthStreamingMode(bytes.size)
                runCatching { http.outputStream.use { it.write(bytes) } }
                val code = http.responseCode
                http.disconnect()
                if (code == 403) throw VfsException.AccessDenied(path)
                if (code !in 200..299) throw VfsException.Io(path, IllegalStateException("HTTP $code"))
            }
        }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode =
        throw VfsException.Unsupported("a peer's shared set is not created remotely")

    override suspend fun delete(path: VPath, recursive: Boolean): Unit =
        throw VfsException.Unsupported("only the owner can remove something from their shared set")

    override suspend fun rename(path: VPath, newName: String): VNode =
        throw VfsException.Unsupported("only the owner can rename their shared files")

    /** The token [path] was listed with, listing its folder first when it has not been yet. */
    private suspend fun tokenOf(path: VPath): String {
        synchronized(tokens) { tokens[path.toString()] }?.let { return it }
        val parent = path.parent ?: throw VfsException.NotFound(path)
        list(parent)
        return synchronized(tokens) { tokens[path.toString()] } ?: throw VfsException.NotFound(path)
    }

    private fun forget(path: VPath) {
        synchronized(tokens) { tokens.remove(path.toString()) }
    }

    private fun endpointFor(uuid: String, path: VPath): PeerEndpoint =
        endpoints().firstOrNull { it.uuid == uuid } ?: throw VfsException.NotFound(path)

    private fun connect(e: PeerEndpoint, target: String, method: String, path: VPath): HttpURLConnection =
        runCatching {
            val http = URL("http://${e.host}:${e.port}$target").openConnection() as HttpURLConnection
            http.requestMethod = method
            http.connectTimeout = 8_000
            http.readTimeout = 30_000
            http.setRequestProperty("User-Agent", "Filet")
            e.token?.let {
                http.setRequestProperty("X-Filet-Peer", e.uuid)
                http.setRequestProperty("X-Filet-Token", it)
            }
            http
        }.getOrElse { throw VfsException.Io(path, it) }

    private fun get(e: PeerEndpoint, target: String, path: VPath): String {
        val http = connect(e, target, "GET", path)
        val code = runCatching { http.responseCode }.getOrElse { throw VfsException.Io(path, it) }
        if (code == 401) { http.disconnect(); throw VfsException.AccessDenied(path) }
        if (code !in 200..299) { http.disconnect(); throw VfsException.Io(path, IllegalStateException("HTTP $code")) }
        val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        return body
    }

    companion object {
        const val SCHEME = "peer"

        fun mount(uuid: String): VPath = VPath.of(SCHEME, "/$uuid")
    }
}

/**
 * The names another phone lists, made fit to be path segments. Pure, because it decides what a
 * row is called and which file it fetches.
 */
object PeerNames {
    /**
     * [name] as one path segment: a slash would split it and `.` or `..` would walk out of the
     * folder, so the first is swapped for a look-alike and the others, like an empty name, fall
     * back to [token].
     */
    fun clean(name: String, token: String): String {
        val n = name.replace('/', '∕').replace('\u0000', ' ').trim()
        return if (n.isEmpty() || n == "." || n == "..") token else n
    }

    /**
     * [names] made unique within one listing, in order: a second `a.pdf` becomes `a (2).pdf`.
     * A shared set can hold two things under one name - a file in the share folder and another
     * shared from elsewhere - and two rows at one path would open the same file.
     */
    fun unique(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { n ->
            if (taken.add(n)) return@map n
            val dot = n.lastIndexOf('.').takeIf { it > 0 } ?: n.length
            var i = 2
            var next: String
            do {
                next = n.substring(0, dot) + " ($i)" + n.substring(dot)
                i++
            } while (!taken.add(next))
            next
        }
    }
}
