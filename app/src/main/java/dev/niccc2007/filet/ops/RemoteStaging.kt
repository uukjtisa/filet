package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A local copy of a remote file, for the operations that cannot work without one.
 *
 * ## Why anything needs this
 *
 * Three things take a real path rather than a stream, and no amount of protocol work changes
 * it: `getPackageArchiveInfo`, the zip reader used to open an archive, and Android's package
 * installer. Until now the answer on a mounted share was the refusal *"Inspect APKs from local
 * storage"*, which is accurate and unhelpful.
 *
 * ## What it is not
 *
 * Not a download folder and not something the user maintains. It is a **cache**: bounded,
 * evicted oldest-first, and keyed so it can never serve a copy of something that has since
 * changed. See `REMOTE-FILES.md` for where this sits among the three tiers - it is the last
 * one, and the ranged reads that avoid reaching it at all are the first.
 */
class RemoteStaging(
    private val vfs: Vfs,
    private val ledger: JobLedger,
    private val cacheDir: File,
) {

    /**
     * Where a staged copy of [path] lives, fetching it if it is not there yet.
     *
     * @param size the remote file's size, used both for the cache key and to decide whether
     *   this is worth announcing.
     * @param mtime the remote file's modification time, which is the other half of the key.
     *
     * @return the local file, or null when [path] is not remote - a caller can then use the
     *   real path it already had, and never pays for a copy of a file that is already here.
     */
    suspend fun stage(path: VPath, size: Long, mtime: Long): File? = withContext(Dispatchers.IO) {
        if (!vfs.isRemote(path)) return@withContext null

        val target = File(dir(), keyFor(path, size, mtime))
        // Already here, and the key guarantees it is a copy of THIS version of the file.
        if (target.isFile && target.length() == size) return@withContext target

        // Announced only when the wait is long enough to wonder about. A small file arriving in
        // under a second produces a job row that appears and vanishes, which reads as a glitch.
        val id = if (size >= ANNOUNCE_ABOVE) ledger.start("Fetching ${path.name}") else null

        // Written beside the target and moved into place, so an interrupted fetch can never be
        // mistaken for a complete copy by the length check above.
        val part = File(target.absolutePath + ".part")
        runCatching {
            part.delete()
            vfs.openRead(path).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (id != null && size > 0) {
                            ledger.progress(id, done.toFloat() / size, path.name)
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw java.io.IOException("could not finish the copy")
        }.onFailure {
            part.delete()
            id?.let { j -> ledger.fail(j, FileOperations.readable(it)) }
            throw it
        }
        id?.let { ledger.finish(it) }
        evictDownTo()
        target
    }

    /** Whether [path] is already staged, so a caller can skip asking about a fetch. */
    fun cached(path: VPath, size: Long, mtime: Long): File? =
        File(dir(), keyFor(path, size, mtime)).takeIf { it.isFile && it.length() == size }

    /**
     * The cache key: where it came from, how big it was, and when it changed.
     *
     * All three, because any one alone is wrong. Path alone serves a stale copy after the far
     * end is edited. Size alone collides constantly. The hash keeps a path with a slash or a
     * colon in it from becoming a directory.
     */
    private fun keyFor(path: VPath, size: Long, mtime: Long): String {
        val basis = path.toString() + "|" + size + "|" + mtime
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(basis.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        // The real extension is kept on the end: the zip reader and the package parser both
        // sniff content, but a file with no extension is harder to identify in a bug report.
        val ext = path.name.substringAfterLast('.', "")
        return if (ext.isEmpty()) digest else "$digest.$ext"
    }

    private fun dir(): File = File(cacheDir, "remote").apply { mkdirs() }

    /**
     * Keep the cache under its ceiling, oldest first.
     *
     * Last-modified rather than last-accessed: Android does not reliably update access times,
     * so sorting by them evicts essentially at random.
     */
    private fun evictDownTo() {
        val files = dir().listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        /** The ceiling for staged copies together. */
        const val MAX_BYTES = 512L * 1024 * 1024

        /** Below this, a fetch is quick enough that announcing it is noise. */
        const val ANNOUNCE_ABOVE = 2L * 1024 * 1024
    }
}
