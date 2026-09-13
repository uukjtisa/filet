package dev.niccc2007.filet.vfs.provider

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * The whole filesystem, through root.
 *
 * **libsu, never `Runtime.exec("su")`** (PLAN.md L0). The difference is not style: a fresh
 * `su` process per command is slow, unquoted, and impossible to reason about when a filename
 * contains a space or a newline. libsu keeps one managed shell and escapes for you.
 *
 * Addressing: `root:///data/data/com.example`. Rooted paths are ordinary absolute paths -
 * the scheme is what says "use elevated access to read this", which is exactly the
 * distinction the VFS exists to carry.
 *
 * The provider is registered only when root is actually available, so PLAN.md R1 holds: if
 * the device is not rooted, the scheme does not exist and no UI offers it.
 */
class RootProvider : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME,
        Capability.DELETE, Capability.CREATE_DIR, Capability.RANDOM_ACCESS,
    )

    override suspend fun roots(): List<VNode> = withContext(Dispatchers.IO) {
        // Three entries rather than just "/": these are the three places anyone actually
        // wants root for, and starting at "/" means four taps before anything useful.
        listOf("/", "/data", "/system")
            .map { SuFile(it) }
            .filter { it.exists() }
            .map { it.toNode() }
    }

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val f = SuFile(path.path)
        if (!f.exists()) throw VfsException.NotFound(path)
        if (!f.isDirectory) throw VfsException.NotADirectory(path)
        val kids = f.listFiles() ?: throw VfsException.AccessDenied(path)
        kids.map { SuFile(it.absolutePath).toNode() }
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val f = SuFile(path.path)
        if (f.exists()) f.toNode() else null
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val f = SuFile(path.path)
        if (!f.exists()) throw VfsException.NotFound(path)
        if (f.isDirectory) throw VfsException.IsADirectory(path)
        try { SuFileInputStream.open(f) } catch (e: Exception) { throw VfsException.Io(path, e) }
    }

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = withContext(Dispatchers.IO) {
        try { SuFileOutputStream.open(SuFile(path.path), append) }
        catch (e: Exception) { throw VfsException.Io(path, e) }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = withContext(Dispatchers.IO) {
        val f = SuFile(path.path)
        if (f.exists()) throw VfsException.AlreadyExists(path)
        val ok = if (isDir) f.mkdirs() else f.createNewFile()
        if (!ok) throw VfsException.AccessDenied(path)
        f.toNode()
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = withContext(Dispatchers.IO) {
        val f = SuFile(path.path)
        if (!f.exists()) throw VfsException.NotFound(path)
        val ok = if (recursive) f.deleteRecursive() else f.delete()
        if (!ok) throw VfsException.AccessDenied(path)
    }

    override suspend fun rename(path: VPath, newName: String): VNode = withContext(Dispatchers.IO) {
        val from = SuFile(path.path)
        val to = SuFile(path.parent?.child(newName)?.path ?: throw VfsException.Unsupported("cannot rename a root"))
        if (to.exists()) throw VfsException.AlreadyExists(path)
        if (!from.renameTo(to)) throw VfsException.AccessDenied(path)
        to.toNode()
    }

    override suspend fun moveWithin(from: VPath, to: VPath): VNode? = withContext(Dispatchers.IO) {
        val src = SuFile(from.path)
        val dst = SuFile(to.path)
        if (src.renameTo(dst)) dst.toNode() else null
    }

    override suspend fun freeSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        runCatching { SuFile(path.path).freeSpace }.getOrNull()
    }

    override suspend fun totalSpace(path: VPath): Long? = withContext(Dispatchers.IO) {
        runCatching { SuFile(path.path).totalSpace.takeIf { it > 0 } }.getOrNull()
    }

    /**
     * Root paths are real OS paths, so watchers and `TriggerContentUri` can use them - but
     * only where the *app's own* uid can also see them, which inotify requires. Returning the
     * path is honest; the watcher finding it unreadable is a separate, correct failure.
     */
    override fun osPath(path: VPath): String? = path.path

    private fun SuFile.toNode(): VNode = VNode(
        path = VPath.of(SCHEME, absolutePath),
        isDir = isDirectory,
        size = if (isDirectory) -1L else length(),
        mtime = lastModified(),
        readable = canRead(),
        writable = canWrite(),
        hidden = name.startsWith("."),
    )

    companion object {
        const val SCHEME = "root"

        /**
         * Whether this device can actually give us root.
         *
         * Asked once and cached by libsu. Calling this spawns a shell and, on a rooted
         * device, triggers the superuser prompt - so it is called when the user opts in,
         * never at startup.
         */
        fun isAvailable(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)

        /** Non-blocking probe: true only when root was already granted this session. */
        fun isGranted(): Boolean = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
    }
}
