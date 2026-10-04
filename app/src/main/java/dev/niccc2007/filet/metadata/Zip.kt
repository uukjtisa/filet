package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Reading one file out of a zip held in memory, and writing one back.
 *
 * ## Why not the one in the standard library
 *
 * `ZipFile` needs a path on disk, and this works on bytes that came through the VFS - which may
 * be a WebDAV share with no local file at all. `ZipInputStream` plus `ZipOutputStream` would work
 * on bytes, but round-tripping through them re-compresses every entry and quietly drops the
 * things that are not its business: the extra fields, the external attributes, the order. For an
 * OOXML document that order is not decoration - some readers expect `[Content_Types].xml` first.
 *
 * So entries are copied **still compressed**, byte for byte, and only the one being replaced is
 * deflated afresh. A document whose metadata is edited comes out the same size, in the same
 * order, with the same compression as it went in.
 *
 * ## What is refused
 *
 * Zip64 and encrypted archives. Zip64 moves the real sizes into an extra field this does not
 * read, and an encrypted entry cannot be replaced without the password. Both return null rather
 * than producing an archive that looks finished.
 */
object Zip {

    private const val CD_SIG = 0x02014b50
    private const val LOCAL_SIG = 0x04034b50
    private const val EOCD_SIG = 0x06054b50

    /** A data descriptor after the entry data, which means the local header sizes are zero. */
    private const val FLAG_DESCRIPTOR = 0x08
    private const val FLAG_ENCRYPTED = 0x01

    private const val UNKNOWN_32 = 0xFFFFFFFFL

    data class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Int,
        val size: Int,
        val localAt: Int,
        val cdAt: Int,
        val cdLen: Int,
        val time: Int,
        val date: Int,
        val madeBy: Int,
        val needed: Int,
        val internalAttrs: Int,
        val externalAttrs: Long,
    )

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun putU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun putU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    /**
     * Find the end-of-central-directory record.
     *
     * Searched backwards, because the signature can occur inside compressed data and the last one
     * is the real record. Only the final 64 KB can hold it - that is the size of the comment
     * length field.
     */
    private fun findEocd(b: ByteArray): Int? {
        if (b.size < 22) return null
        val lowest = maxOf(0, b.size - 22 - 0xFFFF)
        var i = b.size - 22
        while (i >= lowest) {
            if (u32(b, i) == EOCD_SIG.toLong()) return i
            i--
        }
        return null
    }

    fun isZip(b: ByteArray): Boolean = findEocd(b) != null && u32(b, 0) == LOCAL_SIG.toLong()

    /** Every entry, in central-directory order, or null if the archive is one this will not touch. */
    fun entries(b: ByteArray): List<Entry>? {
        val eocd = findEocd(b) ?: return null
        val count = u16(b, eocd + 10)
        val cdAt = u32(b, eocd + 16)
        // Both are the zip64 "look in the extra field" marker, and the extra field is not read here.
        if (count == 0xFFFF || cdAt == UNKNOWN_32) return null
        var at = cdAt.toInt()
        val out = ArrayList<Entry>(count)
        for (i in 0 until count) {
            if (at + 46 > b.size || u32(b, at) != CD_SIG.toLong()) return null
            val flags = u16(b, at + 8)
            if ((flags and FLAG_ENCRYPTED) != 0) return null
            val nameLen = u16(b, at + 28)
            val extraLen = u16(b, at + 30)
            val commentLen = u16(b, at + 32)
            if (at + 46 + nameLen + extraLen + commentLen > b.size) return null
            val csize = u32(b, at + 20)
            val size = u32(b, at + 24)
            val localAt = u32(b, at + 42)
            if (csize == UNKNOWN_32 || size == UNKNOWN_32 || localAt == UNKNOWN_32) return null
            out.add(
                Entry(
                    name = String(b, at + 46, nameLen, Charsets.UTF_8),
                    method = u16(b, at + 10),
                    flags = flags,
                    crc = u32(b, at + 16),
                    compressedSize = csize.toInt(),
                    size = size.toInt(),
                    localAt = localAt.toInt(),
                    cdAt = at,
                    cdLen = 46 + nameLen + extraLen + commentLen,
                    time = u16(b, at + 12),
                    date = u16(b, at + 14),
                    madeBy = u16(b, at + 4),
                    needed = u16(b, at + 6),
                    internalAttrs = u16(b, at + 36),
                    externalAttrs = u32(b, at + 38),
                ),
            )
            at += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    /** Where an entry's compressed bytes start, read from its own local header. */
    private fun dataAt(b: ByteArray, e: Entry): Int? {
        val at = e.localAt
        if (at + 30 > b.size || u32(b, at) != LOCAL_SIG.toLong()) return null
        val nameLen = u16(b, at + 26)
        val extraLen = u16(b, at + 28)
        val start = at + 30 + nameLen + extraLen
        if (start + e.compressedSize > b.size) return null
        return start
    }

    /** One entry's contents, decompressed. */
    fun read(b: ByteArray, name: String): ByteArray? {
        val e = entries(b)?.firstOrNull { it.name == name } ?: return null
        val at = dataAt(b, e) ?: return null
        val raw = b.copyOfRange(at, at + e.compressedSize)
        return when (e.method) {
            0 -> raw
            8 -> runCatching { inflate(raw, e.size) }.getOrNull()
            else -> null
        }
    }

    private fun inflate(raw: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(if (expected > 0) expected else raw.size * 3)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Rebuild the archive with one entry's contents replaced.
     *
     * Every other entry is copied in its compressed form with its own order and attributes, and
     * the local extra field is dropped on purpose: it carries alignment padding whose whole point
     * is a byte position that the rebuild changes anyway, and a stale alignment field is worse
     * than none. The data descriptor flag is cleared because the real sizes are written into the
     * local header here, so the descriptor would be a second, contradicting copy.
     */
    fun replace(b: ByteArray, name: String, content: ByteArray): ByteArray? {
        val list = entries(b) ?: return null
        if (list.none { it.name == name }) return null
        val out = ByteArrayOutputStream(b.size + content.size)
        val offsets = HashMap<String, Int>(list.size)
        val payloads = HashMap<String, Triple<Int, Long, ByteArray>>(1)

        for (e in list) {
            offsets[e.name] = out.size()
            val isTarget = e.name == name
            val method: Int
            val crc: Long
            val raw: ByteArray
            val size: Int
            if (isTarget) {
                val crc32 = CRC32().apply { update(content) }
                // Stored stays stored: an entry the producer chose not to compress - the
                // mimetype entry in an ODF package, for instance - has to come back the same way.
                method = if (e.method == 0) 0 else 8
                raw = if (method == 0) content else deflate(content)
                crc = crc32.value
                size = content.size
                payloads[e.name] = Triple(method, crc, raw)
            } else {
                method = e.method
                crc = e.crc
                val at = dataAt(b, e) ?: return null
                raw = b.copyOfRange(at, at + e.compressedSize)
                size = e.size
            }
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            putU32(out, LOCAL_SIG.toLong())
            putU16(out, e.needed)
            putU16(out, e.flags and FLAG_DESCRIPTOR.inv())
            putU16(out, method)
            putU16(out, e.time)
            putU16(out, e.date)
            putU32(out, crc)
            putU32(out, raw.size.toLong())
            putU32(out, size.toLong())
            putU16(out, nameBytes.size)
            putU16(out, 0)
            out.write(nameBytes)
            out.write(raw)
        }

        val cdStart = out.size()
        for (e in list) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val replaced = payloads[e.name]
            val method = replaced?.first ?: e.method
            val crc = replaced?.second ?: e.crc
            val csize = replaced?.third?.size ?: e.compressedSize
            val size = if (replaced != null) content.size else e.size
            putU32(out, CD_SIG.toLong())
            putU16(out, e.madeBy)
            putU16(out, e.needed)
            putU16(out, e.flags and FLAG_DESCRIPTOR.inv())
            putU16(out, method)
            putU16(out, e.time)
            putU16(out, e.date)
            putU32(out, crc)
            putU32(out, csize.toLong())
            putU32(out, size.toLong())
            putU16(out, nameBytes.size)
            putU16(out, 0)
            putU16(out, 0)
            putU16(out, 0)
            putU16(out, e.internalAttrs)
            putU32(out, e.externalAttrs)
            putU32(out, (offsets[e.name] ?: return null).toLong())
            out.write(nameBytes)
        }
        val cdEnd = out.size()
        putU32(out, EOCD_SIG.toLong())
        putU16(out, 0)
        putU16(out, 0)
        putU16(out, list.size)
        putU16(out, list.size)
        putU32(out, (cdEnd - cdStart).toLong())
        putU32(out, cdStart.toLong())
        // The archive comment is carried across rather than dropped: ContainerComments can set
        // one, and an edit to a document's title has no business erasing it.
        val comment = findEocd(b)?.let { at ->
            val len = u16(b, at + 20)
            if (len > 0 && at + 22 + len <= b.size) b.copyOfRange(at + 22, at + 22 + len) else null
        }
        putU16(out, comment?.size ?: 0)
        if (comment != null) out.write(comment)
        return out.toByteArray()
    }
}
