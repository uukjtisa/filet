package dev.niccc2007.filet.vfs

import java.io.InputStream
import java.io.OutputStream

/**
 * One storage backend. Local, SAF, archive, APK, root, SMB — each implements this and
 * nothing above L0 knows which one it is holding.
 *
 * Modelled on the shape of Java NIO2's provider model (PLAN.md L0). Deliberately narrow:
 * cross-provider work (copy, move) belongs to [Vfs], because a provider cannot stream to a
 * backend it has never heard of.
 */
interface FileSystemProvider {

    /** URI scheme this provider answers to, e.g. "local". Unique across providers. */
    val scheme: String

    val capabilities: Set<Capability>

    /**
     * Whether this backend's files live on another device.
     *
     * Not a capability: it changes nothing about what may be done, only what a refusal MEANS and
     * where the reader would have to go to change it. A local volume that is read-only is a
     * property of this phone; a share that is read-only is a switch on somebody else's.
     *
     * It is also the flag every action pipeline reads before it starts, because remote work has
     * to leave the calling thread and local work is merely better off for it.
     */
    val remote: Boolean get() = false

    /** Roots this provider offers, e.g. internal storage and each SD card. */
    suspend fun roots(): List<VNode>

    /** @throws VfsException.NotFound @throws VfsException.NotADirectory */
    suspend fun list(path: VPath): List<VNode>

    /**
     * The same listing, handed over in pieces.
     *
     * Exists because time-to-first-row and time-to-last-row were the same number, and on a
     * folder of a few thousand entries that is seconds of a screen with nothing on it. A backend
     * that can produce rows incrementally overrides this and calls [onChunk] as it goes; one
     * that cannot - a network share that answers a whole directory in one response, an archive
     * whose central directory is read in full or not at all - inherits the default and is not
     * made to pretend otherwise.
     *
     * Contract for an override: [onChunk] is called at least once for a non-empty folder,
     * chunks are disjoint, and their concatenation is exactly what [list] would have returned.
     * Order between chunks is the backend's own and carries no promise - the caller sorts.
     */
    suspend fun list(path: VPath, onChunk: suspend (List<VNode>) -> Unit) {
        onChunk(list(path))
    }

    /** Null when absent — absence is an ordinary answer, not an exception. */
    suspend fun stat(path: VPath): VNode?

    /**
     * How many entries a directory holds, without building a row for each one.
     *
     * The default answers by listing, which is correct everywhere and wasteful where a backend
     * can count without describing. A local directory can: `File.list()` is one readdir and
     * hands back names, where a listing stats every child on top of that.
     *
     * Null when the count cannot be had at all - no permission, not a directory, a backend whose
     * protocol has no cheap form. A caller shows nothing rather than a wrong number.
     */
    suspend fun countChildren(path: VPath): Int? =
        runCatching { list(path).size }.getOrNull()

    suspend fun openRead(path: VPath): InputStream

    /** @param append false truncates. */
    suspend fun openWrite(path: VPath, append: Boolean = false): OutputStream

    /**
     * Whether part of a file can be read without reading the whole of it.
     *
     * Not the same question as [Capability.RANDOM_ACCESS], which is a static claim about the
     * BACKEND. This is asked of a live connection, because a remote's answer depends on the
     * server at the far end - Filet's own serves ranges, an old NAS may not - and getting it
     * wrong is silent: a server that ignores the header returns the whole file, and a caller
     * expecting bytes 4000-4100 would read the first hundred bytes and treat them as an index.
     */
    suspend fun supportsRanges(path: VPath): Boolean = false

    /**
     * Bytes `[from, until)` of [path].
     *
     * Only meaningful where [supportsRanges] is true. The default refuses rather than silently
     * reading from the start, which would produce wrong data that looks like right data.
     */
    suspend fun readRange(path: VPath, from: Long, until: Long): ByteArray =
        throw VfsException.Unsupported("ranged reads")

    /** @throws VfsException.AlreadyExists */
    suspend fun create(path: VPath, isDir: Boolean): VNode

    /** @param recursive required to remove a non-empty directory. */
    suspend fun delete(path: VPath, recursive: Boolean = false)

    /** Rename within the same parent. Cross-directory moves go through [Vfs.move]. */
    suspend fun rename(path: VPath, newName: String): VNode

    /**
     * Same-provider relocation, when the backend can do it without copying bytes.
     * Return null when it cannot; [Vfs.move] then falls back to copy-then-delete.
     */
    suspend fun moveWithin(from: VPath, to: VPath): VNode? = null

    /** Free bytes on the volume containing [path], or null when unknown. */
    suspend fun freeSpace(path: VPath): Long? = null

    /**
     * Total bytes on the volume containing [path], or null when unknown.
     *
     * Free alone answers the wrong question: "12 GB free" describes a nearly-empty SD card and
     * a nearly-full phone identically. The denominator is what makes the figure mean anything,
     * so it is a first-class query rather than something a caller guesses.
     */
    suspend fun totalSpace(path: VPath): Long? = null

    /**
     * The operating-system path, when this backend has one.
     *
     * The single documented hole in R3, and it is narrow on purpose: `FileObserver` and
     * `JobInfo.TriggerContentUri` are kernel facilities that take a real path and cannot be
     * expressed through the VFS. Asking the provider for it keeps the knowledge of *whether*
     * a backend has an OS path inside L0, where it belongs - a SAF tree or an SMB share
     * correctly answers null and the caller degrades to polling instead of guessing.
     */
    fun osPath(path: VPath): String? = null
}
