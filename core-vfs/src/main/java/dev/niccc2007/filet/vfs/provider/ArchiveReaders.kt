package dev.niccc2007.filet.vfs.provider

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/**
 * Reading the inside of an archive, one small interface per container shape.
 *
 * The provider above this does addressing, directory synthesis and VFS types. This does the
 * formats, and the split is what keeps `ArchiveProvider` from turning into a `when` over ten
 * libraries.
 *
 * **The cost model is not the same for every format, and pretending it is would be the bug.**
 * A zip and a 7z carry an index, so listing one is a seek and a small read whatever it
 * weighs. A tar has no index at all: the only way to know what is in it is to read it from
 * the front, and through gzip or xz that means decompressing the whole thing. So tar listings
 * are cached, and reading a member out of one is deliberately a second pass rather than an
 * attempt to hold the archive open.
 */
internal data class Member(
    /** Always with forward separators and no leading one, whatever the format stored. */
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

internal interface ArchiveReader {
    /** Every member, flattened. Directories may be implicit and are synthesised above. */
    fun members(): List<Member>

    /** @return a stream over one member, or null when the archive has no such member. */
    fun open(name: String): InputStream?
}

/** Normalise a stored path: `./a\b/` and `/a/b` both become `a/b`. */
internal fun normaliseMember(raw: String): String =
    raw.replace('\\', '/').removePrefix("./").trim('/')

// ── zip ─────────────────────────────────────────────────────────────────────

/**
 * `java.util.zip`, which the whole APK subsystem already leans on.
 *
 * Kept rather than moved to commons-compress: it reads the central directory, it is what
 * `ApkTools` and `ArchiveFacts` already use, and swapping the reader under the APK work to
 * gain nothing measurable is how a working feature breaks.
 */
internal class ZipReader(private val file: File) : ArchiveReader {

    override fun members(): List<Member> = ZipFile(file).use { zip ->
        zip.entries().asSequence().map {
            Member(normaliseMember(it.name), it.isDirectory, if (it.isDirectory) -1L else it.size, it.time)
        }.toList()
    }

    override fun open(name: String): InputStream? {
        val zip = ZipFile(file)
        val entry = zip.getEntry(name) ?: zip.getEntry("$name/")
        if (entry == null || entry.isDirectory) {
            zip.close()
            return null
        }
        // The stream owns the ZipFile: closing the stream closes the archive, or every
        // preview leaks a handle until the collector happens to care.
        val src = zip.getInputStream(entry)
        return object : InputStream() {
            override fun read() = src.read()
            override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
            override fun available() = src.available()
            override fun close() { src.close(); zip.close() }
        }
    }
}

// ── tar, with or without a compressor in front of it ────────────────────────

/**
 * A tar stream. No index, so listing reads the whole archive.
 *
 * The member list is cached by path, size and modification time - the same key the thumbnail
 * cache uses - so walking three levels into a 200 MB `.tar.gz` decompresses it once rather
 * than once per directory.
 */
internal class TarReader(private val file: File) : ArchiveReader {

    override fun members(): List<Member> = MemberCache.get(file) {
        openTar().use { tar ->
            val out = ArrayList<Member>(64)
            var entry = tar.nextEntry
            while (entry != null) {
                out += Member(
                    normaliseMember(entry.name),
                    entry.isDirectory,
                    if (entry.isDirectory) -1L else entry.size,
                    entry.lastModifiedDate?.time ?: 0L,
                )
                if (out.size >= MAX_MEMBERS) break
                entry = tar.nextEntry
            }
            out
        }
    }

    override fun open(name: String): InputStream? {
        val tar = openTar()
        var entry = tar.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && normaliseMember(entry.name) == name) return tar
            entry = tar.nextEntry
        }
        tar.close()
        return null
    }

    /** Wrap the file in whatever compressor its name says, then in the tar reader. */
    private fun openTar(): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        val decompressed: InputStream = when {
            endsWithAny("tar.gz", "tgz") -> GzipCompressorInputStream(raw, true)
            endsWithAny("tar.bz2", "tbz2", "tbz") -> BZip2CompressorInputStream(raw, true)
            endsWithAny("tar.xz", "txz") -> XZCompressorInputStream(raw, true)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }

    private fun endsWithAny(vararg suffixes: String): Boolean {
        val lower = file.name.lowercase()
        return suffixes.any { lower.endsWith(".$it") }
    }

    private companion object {
        /**
         * A tar with more members than this is almost certainly a backup image, and reading
         * the whole index costs the whole archive. The listing says so by stopping.
         */
        const val MAX_MEMBERS = 50_000
    }
}

// ── 7z ──────────────────────────────────────────────────────────────────────

/**
 * 7z, which is indexed like a zip but needs a seekable file rather than a stream.
 *
 * That is why `ArchiveFormat.needsRealPath` exists: a `.7z` sitting inside another archive
 * has no OS path, and the honest answer is to say so rather than to spool a gigabyte into the
 * cache to find out what is in it.
 */
internal class SevenZipReader(private val file: File) : ArchiveReader {

    override fun members(): List<Member> = MemberCache.get(file) {
        SevenZFile.builder().setFile(file).get().use { z ->
            z.entries.map {
                Member(
                    normaliseMember(it.name),
                    it.isDirectory,
                    if (it.isDirectory) -1L else it.size,
                    runCatching { it.lastModifiedDate.time }.getOrDefault(0L),
                )
            }
        }
    }

    override fun open(name: String): InputStream? {
        val z = SevenZFile.builder().setFile(file).get()
        var entry = z.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && normaliseMember(entry.name) == name) {
                val src = z.getInputStream(entry)
                return object : InputStream() {
                    override fun read() = src.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = src.read(b, off, len)
                    override fun close() { src.close(); z.close() }
                }
            }
            entry = z.nextEntry
        }
        z.close()
        return null
    }
}

// ── one compressed file ─────────────────────────────────────────────────────

/**
 * `notes.txt.gz` and friends: a compressor with no container behind it.
 *
 * Exactly one member, named by dropping the compression suffix, with an unknown size -
 * gzip stores the uncompressed length modulo 4 GB in a trailer and bzip2 and xz store nothing
 * at all, so reporting a number here would mean reading the file to invent it.
 */
internal class StreamReader(private val file: File) : ArchiveReader {

    private val memberName: String get() = Archives.baseName(file.name).ifEmpty { "content" }

    override fun members(): List<Member> =
        listOf(Member(memberName, isDir = false, size = -1L, mtime = file.lastModified()))

    override fun open(name: String): InputStream? {
        if (name != memberName) return null
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".gz") -> GzipCompressorInputStream(raw, true)
            lower.endsWith(".bz2") -> BZip2CompressorInputStream(raw, true)
            lower.endsWith(".xz") -> XZCompressorInputStream(raw, true)
            else -> raw
        }
    }
}

// ── the cache that makes a tar bearable ─────────────────────────────────────

/**
 * Member lists, keyed by path, length and modification time.
 *
 * Same key as the thumbnail cache and for the same reason: an edited archive gets a fresh
 * listing and a renamed one keeps its old, so walking back up a tree is free. Small on
 * purpose - six archives is more than anyone has open at once, and each entry is a list of
 * strings that can run to tens of thousands.
 */
internal object MemberCache {

    private const val CAPACITY = 6

    private val map: MutableMap<String, List<Member>> =
        Collections.synchronizedMap(object : LinkedHashMap<String, List<Member>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Member>>) =
                size > CAPACITY
        })

    fun get(file: File, build: () -> List<Member>): List<Member> {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        map[key]?.let { return it }
        val built = build()
        map[key] = built
        return built
    }
}

/** Pick the reader for a file, or null when nothing here can read it. */
internal fun readerFor(file: File, format: ArchiveFormat?): ArchiveReader? = when (format?.kind) {
    ArchiveKind.ZIP -> ZipReader(file)
    ArchiveKind.TAR -> TarReader(file)
    ArchiveKind.SEVEN_ZIP -> SevenZipReader(file)
    ArchiveKind.STREAM -> StreamReader(file)
    // RAR goes through libarchive, and only when its .so actually loaded. A build without the
    // native module reports "cannot read" rather than crashing on a missing symbol.
    ArchiveKind.RAR -> if (RarNative.available) RarReader(file) else null
    null -> null
}

/**
 * Member names for the index.
 *
 * Public, unlike the readers above, because the index needs to look inside an archive and the
 * provider layer is the only thing allowed to. Before this the index had its own `ZipFile`
 * loop, which is why `inzip:` found things in a zip and nothing at all in a tar.gz.
 *
 * @param truncated true when the archive has more members than [MAX]. Recorded rather than
 *   hidden, so a search can say "and more" instead of quietly claiming completeness.
 */
object ArchiveMembers {

    /** Enough to cover a source tree; past it the cost stops being worth the recall. */
    const val MAX = 5_000

    data class Listing(val names: List<String>, val truncated: Boolean)

    /**
     * @param osPath a real path. An archive with none - one nested inside another - returns
     *   null rather than being spooled to the cache during a background crawl.
     * @return null when the file is not an archive Filet reads, or cannot be read at all.
     */
    fun of(osPath: String, max: Int = MAX): Listing? {
        val file = File(osPath)
        val format = Archives.of(file.name) ?: return null
        if (!format.canList) return null
        val reader = readerFor(file, format) ?: return null
        val all = runCatching { reader.members() }.getOrNull() ?: return null
        val names = all.asSequence().filterNot { it.isDir }.map { it.name }.take(max).toList()
        if (names.isEmpty()) return null
        return Listing(names, truncated = all.count { !it.isDir } > names.size)
    }
}
