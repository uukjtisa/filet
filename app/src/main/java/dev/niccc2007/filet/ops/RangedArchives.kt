package dev.niccc2007.filet.ops

import dev.niccc2007.filet.vfs.RangedBlocks
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveKind
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.Archives
import dev.niccc2007.filet.vfs.provider.RangedMounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * Zips on another device opened where they lie, rather than copied to the phone first. Step 2 of
 * `REMOTE-FILES.md`.
 *
 * ## The fault this fixes
 *
 * Opening a zip on a share copied the whole of it before showing a single name, because a zip's
 * table of contents is at its end and the only reader that could seek was the one on a local
 * file. A 2 GB backup on a desktop share was a 2 GB download to look inside. Read through ranges,
 * it is a few requests of 64 KB whatever the size, and opening a member fetches that member.
 *
 * ## Which files, decided per file by [mount]
 *
 * Read in place only when all of these hold; otherwise [mount] says null and the caller stages a
 * copy through [RemoteStaging], as before:
 * - **A zip by its name** - zip, apk, jar and the rest of [ArchiveKind.ZIP]. A tar.gz or a 7z has
 *   to be read from the front, so ranges cannot help: `REMOTE-FILES.md`, tier 3.
 * - **At least [WHOLE_BELOW].** Two megabytes fetched whole is quicker than the requests it takes
 *   to avoid fetching them.
 * - **A server that honours ranges**, asked of the live connection: one that ignores them would
 *   send the whole file in answer to every block.
 * - **It opens.** The table of contents is read once before the folder is shown. A zip split
 *   across parts, or anything else that will not open this way, falls back to a copy rather than
 *   showing an error for a file the copy would have opened.
 *
 * ## The stand-in
 *
 * See [RangedMounts]: a small file in the cache, named as the zip is, holding the zip's path, size
 * and modification time. Its folder is named from all three, so a zip changed on the far end gets
 * a new stand-in and is never listed from the old one's table of contents. It sits under Filet's
 * cache, so writing into it is refused like writing into a staged copy.
 *
 * @param vfs read when a block is fetched, not when this is built: this is built before the Vfs
 *   it reads through, because the archive provider inside that Vfs needs it.
 * @param dirOf the remote-files cache; stand-ins go in its [FOLDER]. A lambda for the reason
 *   [RemoteStaging]'s is one.
 */
class RangedArchives(
    private val vfs: () -> Vfs,
    private val dirOf: () -> File,
) : RangedMounts {

    /** The bytes of the zips read lately, so a second visit costs no request at all. */
    private val open: MutableMap<String, RangedBlocks> =
        Collections.synchronizedMap(object : LinkedHashMap<String, RangedBlocks>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RangedBlocks>?) =
                this.size > OPEN
        })

    // Asked every time a menu is drawn over an archive, local ones included, so the shape of the
    // path rules most out before the folder - which can mean a disk check - is looked at.
    override fun holds(host: File): Boolean =
        host.parentFile?.parentFile?.name == FOLDER &&
            host.absolutePath.startsWith(dir().absolutePath + File.separator)

    override fun blocksOf(host: File): RangedBlocks? {
        if (!holds(host)) return null
        open[host.absolutePath]?.let { return it }
        val target = runCatching { StandIn.decode(host.readText()) }.getOrNull() ?: return null
        val path = runCatching { VPath.parse(target.path) }.getOrNull() ?: return null
        // A stand-in from an earlier run - a restored tab, a recent entry - may name a zip that
        // has changed since. Its old table of contents would point into the new bytes, so it is
        // refused and opened again from the drive. By size alone: the modification time a
        // listing reports and the one a stat reports can differ in precision on the same file.
        val now = runCatching { runBlocking(Dispatchers.IO) { vfs().stat(path) } }.getOrNull()
        if (now == null || now.size != target.size) return null
        val blocks = RangedBlocks(target.size) { from, until ->
            runBlocking(Dispatchers.IO) { vfs().readRange(path, from, until) }
        }
        open[host.absolutePath] = blocks
        return blocks
    }

    /**
     * A stand-in to open [node] through, or null to stage a copy instead. See the class note
     * for when it is which. Never throws: every failure here is a reason to fall back.
     */
    suspend fun mount(node: VNode): File? = withContext(Dispatchers.IO) {
        val v = vfs()
        if (!v.isRemote(node.path)) return@withContext null
        if (!worthIt(node.name, node.size)) return@withContext null
        if (!runCatching { v.supportsRanges(node.path) }.getOrDefault(false)) {
            dev.niccc2007.filet.log.FiletLog.i("ranged", "no ranges from the server, staging " + node.name)
            return@withContext null
        }
        val standIn = runCatching { write(node) }.getOrElse {
            dev.niccc2007.filet.log.FiletLog.w("ranged", "could not write a stand-in for " + node.path, it)
            return@withContext null
        }
        // Read the table of contents now. What it costs is paid once, and the listing the
        // folder opens on is then already held.
        val listed = runCatching { v.list(ArchiveProvider.mount(standIn.absolutePath)) }
        listed.exceptionOrNull()?.let {
            dev.niccc2007.filet.log.FiletLog.w("ranged", "did not open in place, staging " + node.path, it)
            open.remove(standIn.absolutePath)
            standIn.parentFile?.deleteRecursively()
            return@withContext null
        }
        open[standIn.absolutePath]?.let { b ->
            dev.niccc2007.filet.log.FiletLog.i(
                "ranged",
                "opened " + node.name + " (" + node.size + " bytes) in " + b.requests + " requests, " + b.fetched + " bytes",
            )
        }
        standIn
    }

    private fun dir(): File = File(dirOf(), FOLDER)

    private fun write(node: VNode): File {
        val dir = dir().apply { mkdirs() }
        val folder = File(dir, keyOf(node.path.toString(), node.size, node.mtime))
        val file = File(folder, StandIn.fileName(node.name))
        if (file.isFile) {
            // Seen before: marked as used, so trimming keeps it.
            folder.setLastModified(System.currentTimeMillis())
            return file
        }
        folder.mkdirs()
        file.writeText(StandIn(node.path.toString(), node.size, node.mtime).encode())
        trim(dir)
        return file
    }

    /** At most [KEEP] stand-ins, the least recently used removed first. A few hundred bytes each. */
    private fun trim(dir: File) {
        val folders = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (folders.size <= KEEP) return
        for (f in folders.sortedBy { it.lastModified() }.take(folders.size - KEEP)) {
            f.listFiles()?.forEach { open.remove(it.absolutePath) }
            f.deleteRecursively()
        }
    }

    /**
     * What a stand-in holds: where the zip is, how big, and when it changed. Pure, so the
     * format is a test.
     */
    data class StandIn(val path: String, val size: Long, val mtime: Long) {
        fun encode(): String = JSONObject().put("path", path).put("size", size).put("mtime", mtime).toString()

        companion object {
            fun decode(text: String): StandIn? = runCatching {
                val o = JSONObject(text)
                StandIn(o.getString("path"), o.getLong("size"), o.getLong("mtime"))
            }.getOrNull()?.takeIf { it.path.isNotEmpty() && it.size >= 0 }

            /**
             * The zip's own name, so a breadcrumb and an archive's label read as they would for
             * the real file. Characters a memory card's file system refuses become `_`, since the
             * cache can be moved onto one.
             */
            fun fileName(name: String): String =
                name.map { if (it in "\\/:*?\"<>|" || it < ' ') '_' else it }.joinToString("").ifEmpty { "archive.zip" }
        }
    }

    companion object {
        /** The folder stand-ins live in, inside the remote-files cache. */
        const val FOLDER = "ranged"

        /** Below this a zip is fetched whole: `REMOTE-FILES.md`, the small-file shortcut. */
        const val WHOLE_BELOW = 2L * 1024 * 1024

        /** Stand-ins kept on disk. */
        const val KEEP = 64

        /** Zips whose blocks are held in memory at once, at up to 2 MB each. */
        const val OPEN = 3

        /** Whether a zip named [name] of [size] bytes is read in place. Pure. */
        fun worthIt(name: String, size: Long): Boolean =
            size >= WHOLE_BELOW && Archives.of(name)?.kind == ArchiveKind.ZIP

        /** The stand-in's folder: where the zip is, how big, and when it changed. Pure. */
        fun keyOf(path: String, size: Long, mtime: Long): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$path|$size|$mtime".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
    }
}
