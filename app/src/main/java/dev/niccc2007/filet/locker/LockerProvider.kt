package dev.niccc2007.filet.locker

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The Locker as a drive: `locker:///`, browsed by the same file list as everything else.
 *
 * Decision: a drive, not a gallery or a screen of its own. Everything the list already does -
 * folders, sorting, the grid, select, rename, move, delete, search - works inside it with no
 * second implementation, and anything added to the list later works there too. A gallery would
 * have been a second file manager with half the features, and a Locker holds documents as well
 * as pictures.
 *
 * Every read and write goes through [LockerVault]: names from the sealed index, bytes from sealed
 * blobs. Locked, every call refuses with [VfsException.AccessDenied], and the pane draws the
 * unlock card instead of the error.
 *
 * No [roots]: the Locker is reached from its own card, never listed as a volume. A volume is
 * crawled, measured, offered in pickers and walked by search - all of which would either fail
 * against a locked Locker or remember what is inside an open one.
 */
class LockerProvider(private val vault: LockerVault) : FileSystemProvider {

    override val scheme: String = LockerPaths.SCHEME

    override val capabilities: Set<Capability> = setOf(
        Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE,
        Capability.CREATE_DIR, Capability.RANDOM_ACCESS,
    )

    override suspend fun roots(): List<VNode> = emptyList()

    override suspend fun list(path: VPath): List<VNode> = guarded(path) {
        vault.read { i ->
            val e = i.get(path.path) ?: throw VfsException.NotFound(path)
            if (!e.dir) throw VfsException.NotADirectory(path)
            i.children(path.path).map { node(it) }
        }
    }

    override suspend fun stat(path: VPath): VNode? = guarded(path) {
        vault.read { i -> i.get(path.path)?.let { node(it) } }
    }

    override suspend fun countChildren(path: VPath): Int? =
        runCatching { vault.read { it.children(path.path).size } }.getOrNull()

    override suspend fun openRead(path: VPath): InputStream = openReadAt(path, 0)

    override suspend fun openReadAt(path: VPath, offset: Long): InputStream = guarded(path) {
        val e = vault.read { it.get(path.path) } ?: throw VfsException.NotFound(path)
        if (e.dir) throw VfsException.IsADirectory(path)
        // Past the end is an empty read, not a damaged file: the format has no chunk there.
        if (offset >= e.size) return@guarded ByteArrayInputStream(ByteArray(0))
        withContext(Dispatchers.IO) { vault.openBlob(e.blob, offset) }
    }

    override suspend fun supportsRanges(path: VPath): Boolean = true

    override suspend fun readRange(path: VPath, from: Long, until: Long): ByteArray {
        if (until <= from) return ByteArray(0)
        return openReadAt(path, from).use { input ->
            val want = (until - from).toInt()
            val out = ByteArray(want)
            var got = 0
            while (got < want) {
                val r = input.read(out, got, want - got)
                if (r < 0) break
                got += r
            }
            if (got == want) out else out.copyOf(got)
        }
    }

    /**
     * Sealed as it is written, into a new blob; the entry points at it only once the stream is
     * closed, so a reader never sees half a file and a replaced file's old blob goes only then.
     */
    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream = guarded(path) {
        if (append) throw VfsException.Unsupported("appending to a file in the Locker")
        val p = path.path
        val parent = LockerIndex.parentOf(p) ?: throw VfsException.IsADirectory(path)
        vault.read { i ->
            if (i.get(parent)?.dir != true) throw VfsException.NotFound(VPath(scheme, parent))
            if (i.get(p)?.dir == true) throw VfsException.IsADirectory(path)
        }
        val origin = vault.takeOrigin(p)
        withContext(Dispatchers.IO) {
            vault.newBlob { id, size ->
                val old = vault.change { i ->
                    val was = i.get(p)
                    i.put(
                        LockerIndex.Entry(
                            path = p, dir = false, blob = id, size = size, mtime = System.currentTimeMillis(),
                            added = was?.added ?: System.currentTimeMillis(),
                            origin = origin ?: was?.origin,
                        ),
                    )
                    if (origin != null) i.note(LockerIndex.Event(LockerIndex.Entry(p, false).name, placeOf(origin), System.currentTimeMillis(), released = false))
                    was?.blob?.takeIf { it.isNotEmpty() && it != id }
                }
                old?.let { vault.deleteBlobs(listOf(it)) }
            }
        }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode = guarded(path) {
        val p = path.path
        if (!isDir) {
            openWrite(path, append = false).close()
            return@guarded stat(path) ?: throw VfsException.NotFound(path)
        }
        val origin = vault.takeOrigin(p)
        vault.change { i ->
            if (i.get(p) != null) throw VfsException.AlreadyExists(path)
            val parent = LockerIndex.parentOf(p) ?: throw VfsException.AlreadyExists(path)
            if (i.get(parent)?.dir != true) throw VfsException.NotFound(VPath(scheme, parent))
            val now = System.currentTimeMillis()
            i.put(LockerIndex.Entry(p, dir = true, mtime = now, added = now, origin = origin))
            if (origin != null) i.note(LockerIndex.Event(LockerIndex.Entry(p, true).name, placeOf(origin), now, released = false))
            node(i.get(p)!!)
        }
    }

    override suspend fun delete(path: VPath, recursive: Boolean) = guarded(path) {
        val blobs = vault.change { i ->
            val e = i.get(path.path) ?: throw VfsException.NotFound(path)
            if (e.dir && !recursive && i.children(path.path).isNotEmpty()) {
                throw VfsException.Io(path, java.io.IOException("folder not empty"))
            }
            i.remove(path.path)
        }
        withContext(Dispatchers.IO) { vault.deleteBlobs(blobs) }
    }

    override suspend fun rename(path: VPath, newName: String): VNode = guarded(path) {
        val parent = LockerIndex.parentOf(path.path) ?: throw VfsException.Unsupported("renaming the Locker")
        val to = LockerIndex.childOf(parent, newName)
        vault.change { i ->
            if (i.get(to) != null) throw VfsException.AlreadyExists(VPath(scheme, to))
            node(i.move(path.path, to))
        }
    }

    override suspend fun moveWithin(from: VPath, to: VPath): VNode? = guarded(from) {
        vault.change { i ->
            if (i.get(to.path) != null) throw VfsException.AlreadyExists(to)
            node(i.move(from.path, to.path))
        }
    }

    override suspend fun freeSpace(path: VPath): Long? = vault.free()

    override suspend fun totalSpace(path: VPath): Long? = vault.total()

    /** Never: a Locker file has no path a kernel facility could be handed. */
    override fun osPath(path: VPath): String? = null

    private fun node(e: LockerIndex.Entry) = VNode(
        path = VPath(scheme, e.path),
        isDir = e.dir,
        size = if (e.dir) -1 else e.size,
        mtime = e.mtime,
    )

    /** Locked is refused as a permission, so the list says so instead of reporting a fault. */
    private inline fun <T> guarded(path: VPath, block: () -> T): T = try {
        block()
    } catch (e: LockerVault.Locked) {
        throw VfsException.AccessDenied(path, e, unauthenticated = true)
    } catch (e: IllegalArgumentException) {
        throw VfsException.Io(path, e)
    }

    companion object {
        /** A place for the history: the folder something came from, without the drive's root. */
        fun placeOf(origin: String): String =
            origin.substringAfter("://").trimEnd('/').substringBeforeLast('/', "").removePrefix("/storage/emulated/0").trimStart('/')
                .ifEmpty { "the top of the drive" }
    }
}
