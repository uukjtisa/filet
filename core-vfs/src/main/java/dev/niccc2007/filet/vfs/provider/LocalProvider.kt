package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.ListChunks
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
    /**
     * Where a row's metadata comes from. See [Stats] for why one call replaced seven.
     *
     * Injected rather than reached for so the fast path is reachable from a plain JVM test:
     * on a desktop there is no `android.system.Os`, [Stats.OS] answers null forever, and
     * without a seam every test would only ever exercise the fallback.
     */
    private val stats: Stats = Stats.OS,
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
        // One PermProbe per listing, not one per process: a cached answer is only sound while
        // the caller's credentials and the folder's bits are the ones it was taken under, and a
        // listing is the largest span where both are certainly unchanged. Holding it longer
        // would trade a correct padlock for a syscall nobody would have noticed.
        val perms = PermProbe { p -> File(p).let { Perm(it.canRead(), it.canWrite()) } }
        kids.map { it.toNode(perms) }
    }

    /**
     * A listing in pieces, because this is the backend where the wait is long enough to see.
     *
     * The directory read is not the expensive part and never was - it is one syscall and it
     * returns every name at once. The cost is the per-entry stat that turns a name into a row,
     * and that is a loop, so it is the thing that can be interrupted to hand over what it has.
     *
     * Chunk boundaries come from [ListChunks], which also decides that an ordinary folder is not
     * chunked at all. Emitting in the order `listFiles` gave them: sorting here would be a
     * guess at a spec this layer is not allowed to know, and the caller sorts anyway.
     */
    override suspend fun list(path: VPath, onChunk: suspend (List<VNode>) -> Unit) {
        val kids = withContext(Dispatchers.IO) {
            val f = path.toFile()
            if (!f.exists()) throw VfsException.NotFound(path)
            if (!f.isDirectory) throw VfsException.NotADirectory(path)
            f.listFiles() ?: throw VfsException.AccessDenied(path)
        }
        // Empty folders still get one call, so a caller can tell "read it, it is empty" from
        // "never finished". Without this an empty folder would spin forever waiting for a chunk.
        if (kids.isEmpty()) {
            onChunk(emptyList())
            return
        }
        val perms = PermProbe { p -> File(p).let { Perm(it.canRead(), it.canWrite()) } }
        var at = 0
        for (size in ListChunks.plan(kids.size)) {
            val slice = withContext(Dispatchers.IO) {
                // The stat storm, and the reason every chunk hops to IO rather than the whole
                // loop once: yielding the thread between chunks is what lets the frame that
                // draws the previous chunk actually run.
                (at until at + size).map { kids[it].toNode(perms) }
            }
            at += size
            onChunk(slice)
        }
    }

    /**
     * Names only, so a count costs one readdir instead of a listing.
     *
     * `list()` here returns the child NAMES; `listFiles()` would allocate a File per entry for
     * nothing. Either way no child is statted, which is the whole saving - on a camera folder
     * that is the difference between about 200 ms and about 700 ms.
     */
    override suspend fun countChildren(path: VPath): Int? = withContext(Dispatchers.IO) {
        val f = path.toFile()
        if (!f.isDirectory) return@withContext null
        // Null, not zero: a directory that cannot be read is unknown, and an unknown count shown
        // as "0 items" is a confident lie about a folder that may be full.
        f.list()?.size
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

    // A local file seeks for free, so the ranged contract is simply true here - and saying so
    // is what lets a caller write one code path that works on a file and on a share.
    override suspend fun supportsRanges(path: VPath): Boolean = true

    override suspend fun readRange(path: VPath, from: Long, until: Long): ByteArray =
        withContext(Dispatchers.IO) {
            require(from >= 0 && until > from) { "bad range $from..$until" }
            java.io.RandomAccessFile(path.toFile(), "r").use { raf ->
                raf.seek(from)
                val want = (until - from).coerceAtMost(raf.length() - from).toInt()
                if (want <= 0) return@use ByteArray(0)
                val buf = ByteArray(want)
                raf.readFully(buf)
                buf
            }
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

    /**
     * One row.
     *
     * @param perms a probe cache shared across a listing, or null for a one-off stat where
     *   there is nothing to share it with.
     *
     * Takes the single-stat route when it can and the old per-field route when it cannot. The
     * fallback is not dead weight: it is what runs under a JVM unit test, and it is what runs
     * for an entry that genuinely cannot be statted, such as a dangling symlink whose name
     * should still appear in the list.
     */
    private fun File.toNode(perms: PermProbe? = null): VNode {
        val st = stats.of(absolutePath)
        if (st == null) return toNodeByField()
        val dir = StatFacts.isDir(st.mode)
        val perm = perms?.of(absolutePath, st) ?: Perm(canRead(), canWrite())
        return VNode(
            path = pathOf(this),
            isDir = dir,
            size = if (dir) -1L else st.size,
            mtime = st.mtimeMillis,
            readable = perm.readable,
            writable = perm.writable,
            hidden = name.startsWith("."),
            inode = st.ino,
        )
    }

    /**
     * A row built the original way, one syscall per field.
     *
     * `stat` and not `lstat`, which is a change from what this used to do and is the point of
     * saying so here. The inode used to come from `lstat` - the link itself - while the size,
     * the type and the time all came from accessors that follow the link to its target. Those
     * two halves described different files whenever a symlink was involved. Following
     * throughout matches what the list has always shown and what a tap has always opened.
     */
    private fun File.toNodeByField() = VNode(
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
