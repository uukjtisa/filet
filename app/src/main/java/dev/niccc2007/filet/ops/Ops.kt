package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.Progress
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the clipboard holds and what pasting it should do. */
enum class PendingOp { COPY, MOVE }

/** What to do when a destination name is already taken. */
enum class Conflict { RENAME, OVERWRITE, SKIP }

data class Clipboard(val items: List<VPath>, val op: PendingOp) {
    val size: Int get() = items.size
}

/** The outcome of a batch: partial success is the common case and must be reportable. */
data class OpResult(val succeeded: Int, val failed: List<Pair<VPath, String>>) {
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * The long-running file operations, with progress and honest partial results.
 *
 * Separate from the ViewModel because these are the parts that must keep working when the
 * screen is gone, and because a copy that reports "done" after failing on file 300 of 900 is
 * the single most damaging bug a file manager can have. Every batch returns what actually
 * succeeded and what did not.
 */
class FileOperations(private val vfs: Vfs, private val ledger: JobLedger) {

    /**
     * @param onConflict what to do when the destination name is taken. Refusing a whole paste
     *   because one of forty files collides is the behaviour users hate; overwriting silently
     *   is the one that loses data. [Conflict.RENAME] is the default for that reason.
     */
    suspend fun copy(items: List<VPath>, into: VPath, onConflict: Conflict = Conflict.RENAME): OpResult =
        batch(items, "Copying", into) { src, prog ->
            vfs.copy(src, into, resolveName(src, into, onConflict), prog)
        }

    suspend fun move(items: List<VPath>, into: VPath, onConflict: Conflict = Conflict.RENAME): OpResult =
        batch(items, "Moving", into) { src, prog ->
            vfs.move(src, into, resolveName(src, into, onConflict), prog)
        }

    /** @return the name to write under, or null to keep the source name. */
    private suspend fun resolveName(src: VPath, into: VPath, policy: Conflict): String? {
        if (vfs.stat(into.child(src.name)) == null) return null
        return when (policy) {
            Conflict.RENAME -> vfs.uniqueChild(into, src.name).name
            Conflict.OVERWRITE -> { vfs.delete(into.child(src.name), recursive = true); null }
            Conflict.SKIP -> throw VfsException.AlreadyExists(into.child(src.name))
        }
    }

    suspend fun delete(items: List<VPath>): OpResult {
        val id = ledger.start("Deleting ${items.size} item(s)")
        val failed = ArrayList<Pair<VPath, String>>()
        var done = 0
        for (p in items) {
            currentCoroutineContext().ensureActive()
            ledger.progress(id, done / items.size.toFloat(), p.name)
            runCatching { vfs.delete(p, recursive = true) }
                .onSuccess { done++ }
                .onFailure { failed += p to readable(it) }
        }
        report(id, done, failed)
        return OpResult(done, failed)
    }

    /**
     * Pack [items] into a zip at [dest].
     *
     * Streams entry by entry through the VFS, so the source can be an SD card over SAF and
     * the destination internal storage without either side being special-cased. Stored
     * without compression for files that are already compressed - re-deflating a 300 MB mp4
     * costs minutes and saves nothing.
     */
    suspend fun zip(items: List<VNode>, dest: VPath): OpResult {
        val id = ledger.start("Compressing ${items.size} item(s)", dest.name)
        val failed = ArrayList<Pair<VPath, String>>()
        var count = 0
        try {
            ZipOutputStream(vfs.openWrite(dest).buffered()).use { zos ->
                for (node in items) {
                    currentCoroutineContext().ensureActive()
                    runCatching { addToZip(zos, node, node.name) { n -> ledger.progress(id, null, n) } }
                        .onSuccess { count++ }
                        .onFailure { failed += node.path to readable(it) }
                }
            }
        } catch (e: Throwable) {
            ledger.fail(id, readable(e))
            return OpResult(count, failed + (dest to readable(e)))
        }
        report(id, count, failed)
        return OpResult(count, failed)
    }

    private suspend fun addToZip(zos: ZipOutputStream, node: VNode, path: String, tick: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (node.isDir) {
            zos.putNextEntry(ZipEntry("$path/"))
            zos.closeEntry()
            for (child in vfs.list(node.path)) addToZip(zos, child, "$path/${child.name}", tick)
            return
        }
        tick(node.name)
        val stored = node.extension in ALREADY_COMPRESSED
        val entry = ZipEntry(path)
        if (stored) {
            // A STORED entry must carry size and CRC up front, which means reading twice.
            // Worth it: deflating an mp4 burns minutes to save nothing.
            val crc = java.util.zip.CRC32()
            var size = 0L
            vfs.openRead(node.path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    size += n
                }
            }
            entry.method = ZipEntry.STORED
            entry.size = size
            entry.compressedSize = size
            entry.crc = crc.value
        }
        zos.putNextEntry(entry)
        vfs.openRead(node.path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                zos.write(buf, 0, n)
            }
        }
        zos.closeEntry()
    }

    /** Copy everything under [archiveRoot] (a mounted archive path) out to [into]. */
    suspend fun extract(archiveRoot: VPath, into: VPath, label: String): OpResult {
        val id = ledger.start("Extracting", label)
        val failed = ArrayList<Pair<VPath, String>>()
        var count = 0
        try {
            val dest = into.child(label.substringBeforeLast('.'))
            if (vfs.stat(dest) == null) vfs.create(dest, isDir = true)
            for (child in vfs.list(archiveRoot)) {
                currentCoroutineContext().ensureActive()
                ledger.progress(id, null, child.name)
                runCatching { vfs.copy(child.path, dest) }
                    .onSuccess { count++ }
                    .onFailure { failed += child.path to readable(it) }
            }
        } catch (e: Throwable) {
            ledger.fail(id, readable(e))
            return OpResult(count, failed + (archiveRoot to readable(e)))
        }
        report(id, count, failed)
        return OpResult(count, failed)
    }

    private suspend fun batch(
        items: List<VPath>,
        verb: String,
        into: VPath,
        each: suspend (VPath, ((Progress) -> Unit)) -> Unit,
    ): OpResult {
        val id = ledger.start("$verb ${items.size} item(s)", into.name)
        val failed = ArrayList<Pair<VPath, String>>()
        var done = 0
        for (src in items) {
            currentCoroutineContext().ensureActive()
            runCatching {
                each(src) { p ->
                    val frac = if (p.total > 0) (p.done.toFloat() / p.total) else null
                    ledger.progress(id, frac, p.currentName)
                }
            }.onSuccess { done++ }.onFailure { failed += src to readable(it) }
        }
        report(id, done, failed)
        return OpResult(done, failed)
    }

    private fun report(id: Long, done: Int, failed: List<Pair<VPath, String>>) {
        if (failed.isEmpty()) ledger.finish(id, "$done item(s)")
        else ledger.fail(id, "${failed.size} failed, $done succeeded")
    }

    companion object {
        private val ALREADY_COMPRESSED = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "mp3", "opus", "ogg", "m4a",
            "aac", "flac", "mp4", "mkv", "webm", "mov", "avi", "zip", "apk", "7z", "rar", "gz", "xz",
        )

        fun readable(t: Throwable): String = when (t) {
            is VfsException.NotFound -> "no longer exists"
            is VfsException.AlreadyExists -> "already exists there"
            is VfsException.AccessDenied -> "permission denied"
            is VfsException.NotADirectory -> "not a folder"
            is VfsException.IsADirectory -> "is a folder"
            is VfsException.Unsupported -> t.message ?: "not supported"
            else -> t.message ?: t::class.simpleName ?: "failed"
        }
    }
}

/**
 * Pick a name that does not collide, the way a desktop does: `report (2).txt`.
 *
 * Refusing a paste because one of forty files collides is the behaviour every user hates;
 * silently overwriting is the behaviour that loses data. This is the third option.
 */
suspend fun Vfs.uniqueChild(dir: VPath, name: String): VPath {
    if (stat(dir.child(name)) == null) return dir.child(name)
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (i in 2..999) {
        val candidate = dir.child("$stem ($i)$ext")
        if (stat(candidate) == null) return candidate
    }
    throw VfsException.AlreadyExists(dir.child(name))
}
