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

    /** Roots this provider offers, e.g. internal storage and each SD card. */
    suspend fun roots(): List<VNode>

    /** @throws VfsException.NotFound @throws VfsException.NotADirectory */
    suspend fun list(path: VPath): List<VNode>

    /** Null when absent — absence is an ordinary answer, not an exception. */
    suspend fun stat(path: VPath): VNode?

    suspend fun openRead(path: VPath): InputStream

    /** @param append false truncates. */
    suspend fun openWrite(path: VPath, append: Boolean = false): OutputStream

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
