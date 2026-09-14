package dev.niccc2007.filet.vfs.provider

import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.InputStream

/**
 * The two readers a multi-part set needs that a single file does not.
 *
 * See [MultiPartOpen] for why each scheme is opened the way it is.
 */

/**
 * A zip spread across `.z01`, `.z02`, … and a final `.zip`.
 *
 * `java.util.zip.ZipFile` cannot read one at all - it looks for the central directory in the
 * file it was handed and a volume set puts it in the last part, with the entries' data in
 * earlier files it has no idea exist. zip4j follows the volumes, so a split set gets its own
 * reader rather than a patched version of the ordinary one.
 */
internal class SplitZipReader(private val file: File) : ArchiveReader {

    override fun members(): List<Member> = ZipFile(file).use { z ->
        z.fileHeaders.map {
            Member(
                normaliseMember(it.fileName),
                it.isDirectory,
                if (it.isDirectory) -1L else it.uncompressedSize,
                runCatching { it.lastModifiedTimeEpoch }.getOrDefault(0L),
            )
        }
    }

    override fun open(name: String): InputStream? {
        val z = ZipFile(file)
        val header = z.fileHeaders.firstOrNull { normaliseMember(it.fileName) == name }
            ?: run { z.close(); return null }
        // The ZipFile owns the volume handles, so it has to outlive the stream. Closed when the
        // caller closes what it was given.
        val inner = z.getInputStream(header)
        return object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun available(): Int = inner.available()
            override fun close() {
                runCatching { inner.close() }
                runCatching { z.close() }
            }
        }
    }
}

/**
 * Numbered sets, joined once and reused.
 *
 * A `.001`/`.002` split is a plain byte split, so the archive IS the concatenation and there is
 * nothing to read until the parts are put back together. Every reader above this seeks, so a
 * streaming join is not an option.
 *
 * Joined into the JVM's temp directory - on Android that is the app's own cache, which the
 * system may clear when space runs short, and which is exactly the right lifetime for this.
 * The cache key includes each part's length, so a set that changed on disk is re-joined rather
 * than read from a stale copy.
 */
internal object JoinedSets {

    private val joined = HashMap<String, File>()

    @Synchronized
    fun materialise(setName: String, parts: List<File>): File {
        val key = setName + "|" + parts.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        joined[key]?.let { if (it.isFile && it.length() > 0) return it }

        val dir = File(System.getProperty("java.io.tmpdir"), "filet-joined").apply { mkdirs() }
        // Named after the set so `Archives.of` reads the right format off it: a join of
        // `backup.7z.001`… has to end up called `backup.7z` or the reader is chosen wrong.
        val target = File(dir, setName)
        MultiPartOpen.join(parts, target)
        joined[key] = target
        return target
    }

    /** Forget and delete every join. For a low-memory signal or an explicit clear. */
    @Synchronized
    fun clear() {
        for (f in joined.values) runCatching { f.delete() }
        joined.clear()
    }
}
