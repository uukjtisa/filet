package dev.niccc2007.filet.vfs.provider

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * RAR and RAR5, through libarchive.
 *
 * The reason this is the one format with native code behind it is in `RarNative`: every RAR
 * decoder published for the JVM descends from RARLAB's UnRAR source, whose licence forbids
 * using it to build a RAR-compatible archiver, and GPL-3 section 7 does not allow that
 * restriction to be added. libarchive's readers are independent and BSD-2-Clause.
 *
 * ## Why a member is extracted to a file rather than streamed
 *
 * RAR is routinely **solid**: entries share one compression window, so getting at the tenth
 * file means decompressing the nine before it. There is no cheap random access to give, and
 * pretending otherwise with a lazy stream would mean holding a decoder open across an
 * arbitrary amount of caller time and re-running it on every seek.
 *
 * So a member is decompressed once into a scratch file, which is then **unlinked while still
 * open**. On Linux that keeps the descriptor valid and the bytes alive until the stream is
 * closed, and guarantees the file cannot be left behind by an early return, a cancelled copy
 * or a crash. There is no cleanup path because there is nothing to clean up.
 */
internal class RarReader(private val file: File) : ArchiveReader {

    /**
     * The entries as libarchive reported them, with their ORIGINAL names.
     *
     * A RAR made on Windows stores its paths with backslashes. The rest of Filet works in
     * forward slashes, so the listing is normalised - but the extractor matches on the stored
     * string, and asking it for the normalised one finds nothing.
     *
     * Held here rather than filled in by [members], and that is not a style choice: [members]
     * goes through [MemberCache], so on a cache hit its lambda never runs. Building the mapping
     * inside it meant that the second time an archive was opened the map was empty, every
     * lookup failed, and tapping a file inside a RAR opened an empty viewer. Found on the
     * phone, not in review.
     *
     * `lazy`, so listing costs nothing until something is actually read.
     */
    private val entries: List<RarNative.Entry> by lazy { RarNative.list(file.absolutePath) }

    override fun members(): List<Member> = MemberCache.get(file) {
        entries.map {
            Member(
                name = normaliseMember(it.path),
                isDir = it.isDirectory,
                // libarchive reports seconds; every other reader here is in milliseconds, and a
                // listing where RAR dates sit in 1970 is the tell that this was missed.
                size = it.size,
                mtime = it.modifiedAt * 1000L,
            )
        }
    }

    override fun open(name: String): InputStream? {
        val wanted = normaliseMember(name)
        val stored = entries.firstOrNull { normaliseMember(it.path) == wanted }?.path ?: return null

        val scratch = File.createTempFile("filet-rar", null)
        var ok = false
        try {
            FileOutputStream(scratch).use { out ->
                if (RarNative.extract(file.absolutePath, stored, out) < 0) return null
            }
            val stream = FileInputStream(scratch)
            // Unlink while open. The bytes stay reachable through this descriptor and vanish
            // the moment it closes, however that happens.
            scratch.delete()
            ok = true
            return stream
        } finally {
            if (!ok) scratch.delete()
        }
    }
}
