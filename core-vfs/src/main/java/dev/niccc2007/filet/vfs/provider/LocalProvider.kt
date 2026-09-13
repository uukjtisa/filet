package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Direct filesystem access for paths this process can touch without SAF.
 *
 * Takes its roots as a parameter rather than reaching for `Environment` itself, so the whole
 * class runs under a plain JVM unit test with a temp directory and needs no Robolectric.
 * The Android wiring lives in [AndroidStorage].
 *
 * Every call hops to [Dispatchers.IO]: a directory listing is a syscall storm and must never
 * touch the main thread.
 */
class LocalProvider(
    private val rootDirs: List<File>,
    override val scheme: String = SCHEME,
) : FileSystemProvider {

    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME,
        Capability.DELETE, Capability.CREATE_DIR, Capability.RANDOM_ACCESS,
    )

    override fun osPath(path: VPath): String? =
        if (path.scheme == scheme) path.path else null

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        rootDirs.filter { it.exists() }.map { it.toNode() }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (!f.exists()) throw VfsException.NotFound(path)
        if (!f.isDirectory) throw VfsException.NotADirectory(path)
        // listFiles() returns null on permission failure, which is indistinguishable from
        // "empty" if you are careless. Treat null as denial, because that is what it means.
        val kids = f.listFiles() ?: throw VfsException.AccessDenied(path)
        kids.map { it.toNode() }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (f.exists()) f.toNode() else null
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (!f.exists()) throw VfsException.NotFound(path)
        if (f.isDirectory) throw VfsException.IsADirectory(path)
        try { FileInputStream(f) } catch (e: IOException) { throw VfsException.Io(path, e) }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream =
        withContext(Dispatchers.IO) {
            val f = path.toFile()
            if (f.isDirectory) throw VfsException.IsADirectory(path)
            f.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw VfsException.AccessDenied(path) }
            try { FileOutputStream(f, append) } catch (e: IOException) { throw VfsException.Io(path, e) }
        }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (f.exists()) throw VfsException.AlreadyExists(path)
        val ok = try {
            if (isDir) f.mkdirs() else {
                f.parentFile?.mkdirs()
                f.createNewFile()
            }
        } catch (e: IOException) {
            throw VfsException.Io(path, e)
        }
        if (!ok) throw VfsException.AccessDenied(path)
        f.toNode()
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (!f.exists()) throw VfsException.NotFound(path)
        if (f.isDirectory && !recursive) {
            val empty = f.list()?.isEmpty() ?: throw VfsException.AccessDenied(path)
            if (!empty) throw VfsException.Unsupported("directory not empty, pass recursive=true: $path")
        }
        if (!f.deleteRecursivelyChecked()) throw VfsException.AccessDenied(path)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        require(!newName.contains('/') && newName.isNotBlank()) { "invalid name: $newName" }
        val f = path.toFile()
        if (!f.exists()) throw VfsException.NotFound(path)
        val target = File(f.parentFile, newName)
        // Case-only renames are a real operation on case-sensitive filesystems and a no-op
        // trap on case-insensitive ones; refusing an existing DIFFERENT file is the honest guard.
        if (target.exists() && target.absolutePath != f.absolutePath) {
            throw VfsException.AlreadyExists(pathOf(target))
        }
        if (!f.renameTo(target)) throw VfsException.AccessDenied(path)
        target.toNode()
    }

    override suspend fun moveWithin(from: VPath, to: VPath): VNode? = withContext(Dispatchers.IO) {
        val src = from.toFile()
        val dst = to.toFile()
        if (!src.exists()) throw VfsException.NotFound(from)
        if (dst.exists()) throw VfsException.AlreadyExists(to)
        dst.parentFile?.mkdirs()
        // renameTo fails across mount points (internal -> SD). Returning null tells Vfs to
        // fall back to copy-then-delete rather than reporting a move that did not happen.
        if (src.renameTo(dst)) dst.toNode() else null
    }

    override suspend fun freeSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        runCatching { path.toFile().usableSpace }.getOrNull()
    }

    // `usableSpace` above, `totalSpace` here - deliberately not `freeSpace`. usable subtracts
    // the reserved blocks an unprivileged app genuinely cannot touch, so used = total - usable
    // matches what the system Storage screen shows.
    override suspend fun totalSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        runCatching { path.toFile().totalSpace.takeIf { it > 0 } }.getOrNull()
    }

    // ── helpers ──

    private fun VPath.toFile(): File {
        require(scheme == this@LocalProvider.scheme) { "wrong provider for $this" }
        return File(path)
    }

    private fun pathOf(f: File): VPath = VPath.of(scheme, f.absolutePath.replace('\\', '/'))

    private fun File.toNode() = VNode(
        path = pathOf(this),
        isDir = isDirectory,
        size = if (isDirectory) -1L else length(),
        mtime = lastModified(),
        readable = canRead(),
        writable = canWrite(),
        hidden = name.startsWith("."),
        inode = inodeOf(this),
    )

    /**
     * The file's inode, or 0 if it cannot be read.
     *
     * `android.system.Os` is the only cheap way to reach `st_ino` - `java.io.File` never
     * exposes it and NIO's `fileKey()` is null on several platforms. It is absent from the
     * JVM test classpath, so the call is guarded and simply reports "unknown" there; every
     * reader of [VNode.inode] already has to handle 0 for archives and network shares.
     */
    private fun inodeOf(f: File): Long = runCatching {
        android.system.Os.lstat(f.absolutePath).st_ino
    }.getOrDefault(0L)

    /** Like deleteRecursively, but reports failure instead of returning a cheerful false. */
    private fun File.deleteRecursivelyChecked(): Boolean {
        if (isDirectory) {
            listFiles()?.forEach { if (!it.deleteRecursivelyChecked()) return false }
        }
        return delete()
    }

    companion object {
        const val SCHEME = "local"
    }
}
