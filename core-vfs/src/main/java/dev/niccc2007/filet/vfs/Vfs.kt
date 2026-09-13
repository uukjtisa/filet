package dev.niccc2007.filet.vfs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

/** Progress for a long operation. [total] is -1 when the size is not known up front. */
data class Progress(val done: Long, val total: Long, val currentName: String)

/**
 * The single entry point everything above L0 uses.
 *
 * Routes by scheme, and owns the operations that span two providers — copy and move —
 * because those cannot belong to either side.
 */
class Vfs(providers: List<FileSystemProvider>) {

    private val byScheme: Map<String, FileSystemProvider> =
        providers.associateBy { it.scheme }.also {
            require(it.size == providers.size) { "duplicate provider scheme" }
        }

    val schemes: Set<String> get() = byScheme.keys

    fun provider(scheme: String): FileSystemProvider =
        byScheme[scheme] ?: throw VfsException.Unsupported("no provider for scheme '$scheme'")

    private fun p(path: VPath) = provider(path.scheme)

    suspend fun roots(): List<VNode> = byScheme.values.flatMap { it.roots() }
    suspend fun list(path: VPath): List<VNode> = p(path).list(path)
    suspend fun stat(path: VPath): VNode? = p(path).stat(path)
    suspend fun openRead(path: VPath): InputStream = p(path).openRead(path)
    suspend fun openWrite(path: VPath, append: Boolean = false): OutputStream = p(path).openWrite(path, append)
    suspend fun create(path: VPath, isDir: Boolean): VNode = p(path).create(path, isDir)
    suspend fun delete(path: VPath, recursive: Boolean = false) = p(path).delete(path, recursive)
    suspend fun rename(path: VPath, newName: String): VNode = p(path).rename(path, newName)
    suspend fun freeSpace(path: VPath): Long? = p(path).freeSpace(path)
    suspend fun totalSpace(path: VPath): Long? = p(path).totalSpace(path)

    /** The OS path behind [path], or null when this backend has none. See the provider doc. */
    fun osPath(path: VPath): String? = p(path).osPath(path)

    /**
     * What the backend behind [path] can do.
     *
     * Exposed so the UI can grey a write action it already knows will be refused, rather than
     * offering it and turning the refusal into an error message (PLAN.md R1). Still a
     * capability *declaration*, not a permission check - a writable provider can refuse an
     * individual file, so callers must still handle the failure.
     */
    fun capabilities(path: VPath): Set<Capability> = p(path).capabilities

    /** Shorthand for the question every write action asks. */
    fun canWrite(path: VPath): Boolean = Capability.WRITE in capabilities(path)

    /**
     * Copy [from] to [into], recursing through directories.
     *
     * @param into the destination *directory*.
     * @return the created node.
     */
    suspend fun copy(
        from: VPath,
        into: VPath,
        rename: String? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): VNode {
        val src = stat(from) ?: throw VfsException.NotFound(from)
        val dest = into.child(rename ?: from.name)
        guardNotIntoItself(from, dest)
        val total = if (src.isDir) -1L else src.size
        val counter = ByteCounter(total, onProgress)
        return copyNode(src, dest, counter)
    }

    /**
     * Move [from] into the directory [into].
     *
     * Uses the provider's own relocation when both sides share a scheme and it supports it;
     * otherwise copies then deletes. The delete only runs after the copy has fully succeeded,
     * so a failure mid-way never destroys the source.
     */
    suspend fun move(
        from: VPath,
        into: VPath,
        rename: String? = null,
        onProgress: ((Progress) -> Unit)? = null,
    ): VNode {
        val dest = into.child(rename ?: from.name)
        guardNotIntoItself(from, dest)
        if (from.scheme == into.scheme) {
            p(from).moveWithin(from, dest)?.let { return it }
        }
        val copied = copy(from, into, rename, onProgress)
        delete(from, recursive = true)
        return copied
    }

    private fun guardNotIntoItself(from: VPath, dest: VPath) {
        if (from == dest) throw VfsException.AlreadyExists(dest)
        if (from.contains(dest)) {
            throw VfsException.Unsupported("cannot copy or move a directory into itself: $from -> $dest")
        }
    }

    private suspend fun copyNode(src: VNode, dest: VPath, counter: ByteCounter): VNode {
        currentCoroutineContext().ensureActive()
        if (stat(dest) != null) throw VfsException.AlreadyExists(dest)

        if (!src.isDir) {
            val out = openWrite(dest)
            try {
                openRead(src.path).use { input -> counter.pump(input, out, src.name) }
            } finally {
                out.close()
            }
            return stat(dest) ?: throw VfsException.Io(dest, IllegalStateException("vanished after copy"))
        }

        val node = create(dest, isDir = true)
        for (child in list(src.path)) {
            copyNode(child, dest.child(child.name), counter)
        }
        return node
    }

    private class ByteCounter(val total: Long, val onProgress: ((Progress) -> Unit)?) {
        private var done = 0L
        suspend fun pump(input: InputStream, out: OutputStream, name: String) {
            val buf = ByteArray(DEFAULT_BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress?.invoke(Progress(done, total, name))
            }
        }
    }

    private companion object {
        // 64 KB: large enough that syscall overhead stops dominating on flash,
        // small enough that cancellation is still responsive between reads.
        const val DEFAULT_BUFFER = 64 * 1024
    }
}
