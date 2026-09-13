package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.InputStream
import java.io.OutputStream

/**
 * FTP and FTPS, over commons-net.
 *
 * Addressing: `ftp:///<connectionId>/pub/notes.txt`.
 *
 * A connection is opened per operation rather than pooled. FTP servers routinely drop idle
 * control channels without saying so, and a pooled client that has silently died fails on the
 * *next* operation with a misleading error - reconnecting is cheaper than diagnosing that.
 *
 * Plain FTP sends credentials in clear text. The connection editor says so; this provider
 * does not pretend otherwise by hiding it.
 */
class FtpProvider(private val connections: NetConnections) : FileSystemProvider {

    override val scheme: String = NetProtocol.FTP.scheme
    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR,
    )

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        connections.all().filter { it.protocol == NetProtocol.FTP }
            .map { VNode(netPath(scheme, it.id, "/"), true, -1L, 0L) }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        withClient(id, path) { client ->
            val files = client.listFiles(remote) ?: throw VfsException.AccessDenied(path)
            files.filter { it.name != "." && it.name != ".." }.map { f ->
                VNode(
                    path = path.child(f.name),
                    isDir = f.isDirectory,
                    size = if (f.isDirectory) -1L else f.size,
                    mtime = f.timestamp?.timeInMillis ?: 0L,
                    hidden = f.name.startsWith("."),
                )
            }
        }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        if (remote == "/") return@withContext VNode(path, true, -1L, 0L)
        runCatching {
            withClient(id, path) { client ->
                // MLST is not universal, so fall back to listing the parent - which is what
                // every FTP client ends up doing.
                val parent = remote.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
                val name = remote.trimEnd('/').substringAfterLast('/')
                val f = client.listFiles(parent)?.firstOrNull { it.name == name }
                if (f == null) null
                else VNode(path, f.isDirectory, if (f.isDirectory) -1L else f.size, f.timestamp?.timeInMillis ?: 0L)
            }
        }.getOrNull()
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val client = connect(id, path)
        runCatching {
            val stream = client.retrieveFileStream(remote) ?: throw VfsException.NotFound(path)
            object : InputStream() {
                override fun read() = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
                override fun close() {
                    stream.close()
                    // The transfer is not finished until the server's reply is read; skipping
                    // this leaves the control channel out of step for every later command.
                    runCatching { client.completePendingCommand() }
                    quit(client)
                }
            }
        }.getOrElse { quit(client); throw translate(it, path) }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val client = connect(id, path)
        runCatching {
            val stream = (if (append) client.appendFileStream(remote) else client.storeFileStream(remote))
                ?: throw VfsException.AccessDenied(path)
            object : OutputStream() {
                override fun write(b: Int) = stream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
                override fun flush() = stream.flush()
                override fun close() {
                    stream.close()
                    runCatching { client.completePendingCommand() }
                    quit(client)
                }
            }
        }.getOrElse { quit(client); throw translate(it, path) }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        withClient(id, path) { client ->
            val ok = if (isDir) client.makeDirectory(remote)
            else client.storeFile(remote, ByteArray(0).inputStream())
            if (!ok) throw VfsException.AccessDenied(path)
            VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
        }
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        withClient(id, path) { client ->
            val isDir = client.listFiles(remote)?.let { it.size > 1 || client.changeWorkingDirectory(remote) } == true
            val ok = if (isDir) {
                if (recursive) removeTree(client, remote) else client.removeDirectory(remote)
            } else client.deleteFile(remote)
            if (!ok) throw VfsException.AccessDenied(path)
        }
    }

    private fun removeTree(client: FTPClient, remote: String): Boolean {
        val files = client.listFiles(remote) ?: return false
        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            val child = "${remote.trimEnd('/')}/${f.name}"
            if (f.isDirectory) removeTree(client, child) else client.deleteFile(child)
        }
        return client.removeDirectory(remote)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        withClient(id, path) { client ->
            val parent = remote.trimEnd('/').substringBeforeLast('/', "")
            if (!client.rename(remote, "$parent/$newName")) throw VfsException.AccessDenied(path)
            VNode(path.parent!!.child(newName), false, -1L, System.currentTimeMillis())
        }
    }

    private inline fun <T> withClient(id: String, path: VPath, block: (FTPClient) -> T): T {
        val client = connect(id, path)
        return try {
            block(client)
        } catch (t: Throwable) {
            throw translate(t, path)
        } finally {
            quit(client)
        }
    }

    private fun connect(id: String, path: VPath): FTPClient {
        val c = connections.byId(id) ?: throw VfsException.NotFound(path)
        val client = if (c.useTls) FTPSClient(false) else FTPClient()
        return runCatching {
            client.connectTimeout = 15_000
            client.connect(c.host, if (c.port > 0) c.port else NetProtocol.FTP.defaultPort)
            val ok = if (c.anonymous) client.login("anonymous", "filet@localhost")
            else client.login(c.user, c.password)
            if (!ok) throw VfsException.AccessDenied(path)
            client.setFileType(FTP.BINARY_FILE_TYPE)
            // Passive mode always: a phone behind NAT cannot accept the inbound data
            // connection active mode asks for.
            client.enterLocalPassiveMode()
            client.controlEncoding = "UTF-8"
            client
        }.getOrElse { quit(client); throw translate(it, path) }
    }

    private fun quit(client: FTPClient) {
        runCatching { if (client.isConnected) { client.logout(); client.disconnect() } }
    }

    private fun translate(t: Throwable, path: VPath): VfsException = when {
        t is VfsException -> t
        else -> VfsException.Io(path, t)
    }
}
