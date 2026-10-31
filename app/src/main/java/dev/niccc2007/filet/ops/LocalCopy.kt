package dev.niccc2007.filet.ops

import android.content.Context
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.data.FiletStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real file on this filesystem for something that may not be one.
 *
 * ## Why anything needs this
 *
 * `PdfRenderer` takes a seekable file descriptor and nothing else - it has to, because a PDF is
 * read by seeking to its trailer and then to each object. A forward-only stream cannot open
 * one, which rules out an entry inside an archive, a file on a share, and a `content://` URI
 * handed over by another app. All three are ordinary cases in this app.
 *
 * ## Why it lives here and not in the ViewModel
 *
 * It was written there first and `check-r3.mjs` refused it, correctly: PLAN.md R3 keeps
 * `java.io.File` out of the layers above storage, so that there is exactly one place to look
 * when somebody asks how this app touches the filesystem. A screen asking for "a local copy of
 * this" is a storage operation, and it belongs beside [RemoteStaging], which is the same idea
 * for archives and already has the cache directory and the key discipline.
 */
class LocalCopy(
    private val context: Context,
    private val vfs: Vfs,
    private val staging: RemoteStaging,
) {

    /**
     * Give back a local file holding [node]'s bytes, or null when there cannot be one.
     *
     * Three paths, cheapest first: a local file already is one, a remote file goes through the
     * staging cache that archives use, and anything else is copied into the cache directory.
     *
     * The copy is capped. Without a limit this reads "open a 4 GB disk image in the PDF reader
     * and fill the phone"; with one it is a refusal the caller can say out loud.
     */
    suspend fun of(node: VNode): File? = withContext(Dispatchers.IO) {
        // Asked of the provider rather than matched against the scheme string, so a provider
        // added later is not silently excluded from the fast path.
        if (!vfs.isRemote(node.path)) {
            val direct = File(node.path.path)
            if (direct.isFile) return@withContext direct
        }
        runCatching { staging.stage(node.path, node.size, node.mtime) }
            .getOrNull()
            ?.let { return@withContext it }
        if (node.size > MAX_BYTES) return@withContext null
        runCatching {
            val folder = FiletStore.dir(context, FiletStore.Area.COPIES)
            // Keyed on size and time as well as the name, so a file edited elsewhere is not
            // read back from a stale copy - the same rule `RemoteStaging` uses.
            val target = File(folder, node.size.toString() + "_" + node.mtime + "_" + safe(node.name))
            if (target.isFile && target.length() == node.size) return@runCatching target
            // Written beside and renamed, so a reader never finds half a file: a process
            // killed mid-copy would otherwise leave something that opens and is truncated.
            val part = File(folder, target.name + ".part")
            vfs.openRead(node.path).use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            if (!part.renameTo(target)) {
                part.delete()
                return@runCatching null
            }
            trim(folder)
            target
        }.getOrNull()
    }

    /**
     * Keep the folder from growing without bound.
     *
     * Least recently read goes first, which for documents is the right order: the one being
     * read was touched a moment ago and the one from last week is the one to lose.
     */
    private fun trim(folder: File) {
        val files = folder.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= CAP) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= CAP) break
            total -= f.length()
            f.delete()
        }
    }

    /** A name that cannot escape the cache folder or exceed what a filesystem will take. */
    private fun safe(name: String): String =
        name.takeLast(64).map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")

    companion object {
        private const val FOLDER = "docs"

        /**
         * The largest file copied out just to be read.
         *
         * 256 MB: more than any real document, and small enough that a mistaken tap on a disk
         * image is a refusal rather than a full cache partition.
         */
        const val MAX_BYTES = 256L * 1024 * 1024

        /** How much of the cache directory these copies may hold between them. */
        const val CAP = 512L * 1024 * 1024
    }
}
