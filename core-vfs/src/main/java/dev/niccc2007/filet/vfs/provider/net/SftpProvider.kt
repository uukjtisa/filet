package dev.niccc2007.filet.vfs.provider.net

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * SFTP, over sshj.
 *
 * Addressing: `sftp:///<connectionId>/home/nic/notes.txt`.
 *
 * **Host key handling is the honest weak point here.** A real client pins the host key on
 * first use and screams if it changes; that needs UI Filet does not have yet, so the
 * verifier is permissive and the connection editor says so in plain words rather than
 * implying a security property that is not there. Pinning is the next thing to build on this
 * provider, not a nice-to-have.
 */
class SftpProvider(private val connections: NetConnections) : FileSystemProvider {

    override val scheme: String = NetProtocol.SFTP.scheme
    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR,
    )

    private val clients = HashMap<String, Pair<SSHClient, SFTPClient>>()

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        connections.all().filter { it.protocol == NetProtocol.SFTP }
            .map { VNode(netPath(scheme, it.id, "/"), isDir = true, size = -1L, mtime = 0L) }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            sftp.ls(remote).map { r ->
                val isDir = r.attributes.type == FileMode.Type.DIRECTORY
                VNode(
                    path = path.child(r.name),
                    isDir = isDir,
                    size = if (isDir) -1L else r.attributes.size,
                    mtime = r.attributes.mtime * 1000L,
                    hidden = r.name.startsWith("."),
                )
            }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            val a = sftp.statExistence(remote) ?: return@runCatching null
            val isDir = a.type == FileMode.Type.DIRECTORY
            VNode(path, isDir, if (isDir) -1L else a.size, a.mtime * 1000L)
        }.getOrNull()
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            val file = sftp.open(remote, EnumSet.of(OpenMode.READ))
            val stream = file.RemoteFileInputStream()
            object : InputStream() {
                override fun read() = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int) = stream.read(b, off, len)
                override fun close() { stream.close(); file.close() }
            }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            val modes = if (append) EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.APPEND)
            else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            val file = sftp.open(remote, modes)
            val stream = file.RemoteFileOutputStream()
            object : OutputStream() {
                override fun write(b: Int) = stream.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
                override fun flush() = stream.flush()
                override fun close() { stream.close(); file.close() }
            }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            if (isDir) sftp.mkdir(remote)
            else sftp.open(remote, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE)).close()
            VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        runCatching {
            val a = sftp.statExistence(remote) ?: throw VfsException.NotFound(path)
            if (a.type == FileMode.Type.DIRECTORY) {
                if (recursive) removeTree(sftp, remote) else sftp.rmdir(remote)
            } else sftp.rm(remote)
        }.getOrElse { throw translate(it, path) }
    }

    private fun removeTree(sftp: SFTPClient, remote: String) {
        for (r in sftp.ls(remote)) {
            val child = "${remote.trimEnd('/')}/${r.name}"
            if (r.attributes.type == FileMode.Type.DIRECTORY) removeTree(sftp, child) else sftp.rm(child)
        }
        sftp.rmdir(remote)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val sftp = clientFor(id, path)
        val parent = remote.trimEnd('/').substringBeforeLast('/', "")
        runCatching {
            val target = "$parent/$newName"
            sftp.rename(remote, target)
            val a = sftp.statExistence(target)
            VNode(path.parent!!.child(newName), a?.type == FileMode.Type.DIRECTORY, a?.size ?: -1L, System.currentTimeMillis())
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun moveWithin(from: VPath, to: VPath): VNode? = withContext(Dispatchers.IO) {
        val (idA, remoteA) = splitNetPath(from)
        val (idB, remoteB) = splitNetPath(to)
        if (idA != idB) return@withContext null
        runCatching {
            val sftp = clientFor(idA, from)
            sftp.rename(remoteA, remoteB)
            VNode(to, false, -1L, System.currentTimeMillis())
        }.getOrNull()
    }

    @Synchronized
    private fun clientFor(id: String, path: VPath): SFTPClient {
        clients[id]?.let { (ssh, sftp) ->
            if (ssh.isConnected && ssh.isAuthenticated) return sftp
            clients.remove(id)
            runCatching { sftp.close() }
            runCatching { ssh.disconnect() }
        }
        val c = connections.byId(id) ?: throw VfsException.NotFound(path)
        return runCatching {
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 15_000
            ssh.timeout = 30_000
            ssh.connect(c.host, if (c.port > 0) c.port else NetProtocol.SFTP.defaultPort)
            ssh.authPassword(c.user, c.password)
            val sftp = ssh.newSFTPClient()
            clients[id] = ssh to sftp
            sftp
        }.getOrElse { throw translate(it, path) }
    }

    @Synchronized
    fun disconnect(id: String) {
        clients.remove(id)?.let { (ssh, sftp) ->
            runCatching { sftp.close() }
            runCatching { ssh.disconnect() }
        }
    }

    private fun translate(t: Throwable, path: VPath): VfsException = when {
        t is VfsException -> t
        t.message?.contains("No such file", true) == true -> VfsException.NotFound(path)
        t.message?.contains("Permission denied", true) == true -> VfsException.AccessDenied(path, t)
        t.message?.contains("Exhausted available authentication", true) == true ->
            VfsException.AccessDenied(path, t)
        else -> VfsException.Io(path, t)
    }
}
