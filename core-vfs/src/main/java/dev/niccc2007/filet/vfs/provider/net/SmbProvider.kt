package dev.niccc2007.filet.vfs.provider.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * Windows / Samba shares, over SMB2 and SMB3.
 *
 * Addressing: `smb:///<connectionId>/Documents/notes.txt`.
 *
 * One `DiskShare` is held open per connection and reused. Re-authenticating for every
 * `list()` would add a full handshake to each directory change, which on a phone's Wi-Fi is
 * the difference between usable and not.
 */
class SmbProvider(private val connections: NetConnections) : FileSystemProvider {

    override val scheme: String = NetProtocol.SMB.scheme
    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR,
    )

    private val client = SMBClient()
    private val shares = HashMap<String, DiskShare>()
    private val sessions = HashMap<String, Pair<Connection, Session>>()

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        connections.all().filter { it.protocol == NetProtocol.SMB }.mapNotNull { c ->
            runCatching {
                VNode(netPath(scheme, c.id, "/"), isDir = true, size = -1L, mtime = 0L)
            }.getOrNull()
        }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        runCatching {
            share.list(smbPath(remote))
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    VNode(
                        path = path.child(info.fileName),
                        isDir = isDir,
                        size = if (isDir) -1L else info.endOfFile,
                        mtime = info.lastWriteTime.toEpochMillis(),
                        hidden = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L,
                    )
                }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        if (remote == "/") return@withContext VNode(path, true, -1L, 0L)
        val share = shareFor(id, path)
        runCatching {
            val p = smbPath(remote)
            val info = share.getFileInformation(p)
            val isDir = share.folderExists(p)
            VNode(
                path = path,
                isDir = isDir,
                size = if (isDir) -1L else info.standardInformation.endOfFile,
                mtime = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        }.getOrNull()
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        runCatching {
            val file = share.openFile(
                smbPath(remote),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            // The handle must close with the stream, or a directory of previews leaks one
            // server-side handle per file until the session is torn down.
            object : InputStream() {
                private val src = file.inputStream
                override fun read() = src.read()
                override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
                override fun available() = src.available()
                override fun close() { src.close(); file.close() }
            }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        runCatching {
            val file = share.openFile(
                smbPath(remote),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            object : OutputStream() {
                private val out = file.getOutputStream(append)
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() { out.close(); file.close() }
            }
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        runCatching {
            if (isDir) share.mkdir(smbPath(remote))
            else share.openFile(
                smbPath(remote),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                null,
            ).close()
            VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        runCatching {
            val p = smbPath(remote)
            if (share.folderExists(p)) share.rmdir(p, recursive) else share.rm(p)
        }.getOrElse { throw translate(it, path) }
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val (id, remote) = splitNetPath(path)
        val share = shareFor(id, path)
        val parent = remote.trimEnd('/').substringBeforeLast('/', "")
        runCatching {
            val p = smbPath(remote)
            val target = smbPath("$parent/$newName")
            if (share.folderExists(p)) {
                share.openDirectory(p, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                    .use { it.rename(target) }
            } else {
                share.openFile(p, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                    .use { it.rename(target) }
            }
            VNode(path.parent!!.child(newName), share.folderExists(target), -1L, System.currentTimeMillis())
        }.getOrElse { throw translate(it, path) }
    }

    @Synchronized
    private fun shareFor(id: String, path: VPath): DiskShare {
        shares[id]?.let { if (it.isConnected) return it else shares.remove(id) }
        val c = connections.byId(id) ?: throw VfsException.NotFound(path)
        return runCatching {
            val connection = client.connect(c.host, if (c.port > 0) c.port else NetProtocol.SMB.defaultPort)
            val auth = if (c.anonymous) AuthenticationContext.anonymous()
            else AuthenticationContext(c.user, c.password.toCharArray(), c.domain.ifEmpty { null })
            val session = connection.authenticate(auth)
            val share = session.connectShare(c.share) as? DiskShare
                ?: throw VfsException.Unsupported("'${c.share}' is not a disk share")
            sessions[id] = connection to session
            shares[id] = share
            share
        }.getOrElse { throw translate(it, path) }
    }

    @Synchronized
    fun disconnect(id: String) {
        runCatching { shares.remove(id)?.close() }
        sessions.remove(id)?.let { (conn, session) ->
            runCatching { session.close() }
            runCatching { conn.close() }
        }
    }

    /** SMB paths use backslashes and no leading separator. */
    private fun smbPath(remote: String): String = remote.trim('/').replace('/', '\\')

    private fun translate(t: Throwable, path: VPath): VfsException = when {
        t is VfsException -> t
        t.message?.contains("STATUS_OBJECT_NAME_NOT_FOUND", true) == true -> VfsException.NotFound(path)
        t.message?.contains("STATUS_ACCESS_DENIED", true) == true -> VfsException.AccessDenied(path, t)
        t.message?.contains("STATUS_LOGON_FAILURE", true) == true ->
            VfsException.AccessDenied(path, t)
        else -> VfsException.Io(path, t)
    }
}
