package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
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
 * Addressing: `peer:///<peerUuid>/<token>`. The second segment is a **token**, never a path -
 * the remote server has no path-shaped endpoint at all, so nothing outside the other device's
 * shared set is expressible from here.
 *
 * The shared set is flat, which is why this provider lists only one level. That is the remote
 * side's design, not a limitation here: a shared set is a set, not a tree.
 */
class PeerProvider(
    private val endpoints: () -> List<PeerEndpoint>,
) : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(Capability.READ, Capability.WRITE)

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        endpoints().map { e ->
            VNode(VPath.of(SCHEME, "/${e.uuid}"), isDir = true, size = -1L, mtime = 0L, writable = false)
        }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val segs = path.segments
        if (segs.isEmpty()) return@withContext roots()
        // Only the peer root is a directory: the shared set is flat by design.
        if (segs.size > 1) throw VfsException.NotADirectory(path)
        val e = endpointFor(segs[0], path)
        val json = get(e, "/api/list", path)
        val obj = runCatching { JSONObject(json) }.getOrElse { throw VfsException.Io(path, it) }
        val files = obj.optJSONArray("files") ?: return@withContext emptyList()
        (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            VNode(
                path = path.child(f.getString("token")),
                isDir = f.optBoolean("dir"),
                size = f.optLong("size", -1L),
                mtime = f.optLong("mtime"),
                writable = false,
            ).withDisplayName(f.optString("name"))
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

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val segs = path.segments
        if (segs.size < 2) throw VfsException.IsADirectory(path)
        val e = endpointFor(segs[0], path)
        val http = connect(e, "/f/${URLEncoder.encode(segs[1], "UTF-8")}", "GET", path)
        val code = http.responseCode
        if (code == 410) { http.disconnect(); throw VfsException.NotFound(path) }
        if (code !in 200..299) { http.disconnect(); throw VfsException.Io(path, IllegalStateException("HTTP $code")) }
        object : InputStream() {
            private val src = http.inputStream
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); http.disconnect() }
        }
    }

    /**
     * Writing to a peer means uploading into its quarantine folder, and only if it allows it.
     *
     * The name has to be known up front, so the VPath's own name carries it - which is why a
     * copy *to* a peer targets `peer:///<uuid>/<filename>` rather than a token.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        if (append) throw VfsException.Unsupported("a peer cannot append")
        val segs = path.segments
        if (segs.size < 2) throw VfsException.Unsupported("nothing to write to")
        val e = endpointFor(segs[0], path)
        val name = URLEncoder.encode(segs[1], "UTF-8")
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
        throw VfsException.Unsupported("a peer's shared set is flat and is not created remotely")

    override suspend fun delete(path: VPath, recursive: Boolean): Unit =
        throw VfsException.Unsupported("only the owner can remove something from their shared set")

    override suspend fun rename(path: VPath, newName: String): VNode =
        throw VfsException.Unsupported("only the owner can rename their shared files")

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
 * A peer's rows are addressed by token but must *read* as filenames.
 *
 * The display name is carried in the path's own last segment for everything else in the VFS;
 * here the last segment is a token, so the name is attached separately and the browser's row
 * renderer reads it from here.
 */
private val displayNames = HashMap<String, String>()

internal fun VNode.withDisplayName(name: String): VNode {
    if (name.isNotEmpty()) synchronized(displayNames) { displayNames[path.toString()] = name }
    return this
}

/** The filename a peer reported for this row, or null when it is an ordinary path. */
fun displayNameOf(path: VPath): String? = synchronized(displayNames) { displayNames[path.toString()] }
