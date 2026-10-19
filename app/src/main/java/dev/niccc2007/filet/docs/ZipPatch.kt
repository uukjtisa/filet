package dev.niccc2007.filet.docs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Replace some entries of a zip and leave every other byte where it was.
 *
 * ## Why not ZipOutputStream
 *
 * Re-writing a package through `ZipInputStream` and `ZipOutputStream` keeps each entry's
 * *content* but recompresses it, drops extra fields and comments, and changes timestamps - so a
 * signed or carefully produced package comes back different in every entry. This works on the
 * raw archive instead: each replaced entry's local header and data are swapped for new ones,
 * everything between them is copied verbatim, and the central directory is copied with only the
 * offsets (and, for a replaced entry, its CRC and sizes) patched. An untouched entry's compressed
 * bytes are identical in the output, which [check] proves rather than assumes.
 *
 * ## What it refuses
 *
 * ZIP64, multi-disk archives, encrypted entries, and compression other than stored or deflate
 * for an entry being replaced. Office never writes these for a document part; a file that has
 * them is refused whole rather than half-handled.
 */
internal object ZipPatch {

    class Entry(
        val name: String,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localOffset: Long,
        /** Where this entry's central directory record starts. */
        val record: Int,
    )

    class Archive(val entries: List<Entry>, val cdOffset: Int, val cdSize: Int, val eocd: Int)

    class Refused(why: String) : IllegalStateException(why)

    private const val LOCAL = 0x04034b50L
    private const val CENTRAL = 0x02014b50L
    private const val END = 0x06054b50L
    private const val DESCRIPTOR = 0x08074b50L
    private const val ZIP64_LOCATOR = 0x07064b50L

    fun open(zip: ByteArray): Archive {
        var eocd = -1
        val floor = maxOf(0, zip.size - 22 - 0xFFFF)
        for (k in zip.size - 22 downTo floor) if (u32(zip, k) == END) { eocd = k; break }
        if (eocd < 0) throw Refused("This is not a zip archive.")
        if (eocd >= 20 && u32(zip, eocd - 20) == ZIP64_LOCATOR) throw Refused("ZIP64 archives are not edited here.")
        if (u16(zip, eocd + 4) != 0 || u16(zip, eocd + 6) != 0) throw Refused("Multi-part archives are not edited here.")
        val count = u16(zip, eocd + 10)
        val cdSize = u32(zip, eocd + 12)
        val cdOffset = u32(zip, eocd + 16)
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) throw Refused("ZIP64 archives are not edited here.")
        if (cdOffset + cdSize > eocd) throw Refused("The archive's directory is damaged.")
        val out = ArrayList<Entry>(count)
        var at = cdOffset.toInt()
        repeat(count) {
            if (u32(zip, at) != CENTRAL) throw Refused("The archive's directory is damaged.")
            val nameLen = u16(zip, at + 28)
            val extraLen = u16(zip, at + 30)
            val commentLen = u16(zip, at + 32)
            out += Entry(
                name = String(zip, at + 46, nameLen, Charsets.UTF_8),
                method = u16(zip, at + 10),
                flags = u16(zip, at + 8),
                crc = u32(zip, at + 16),
                compressedSize = u32(zip, at + 20),
                size = u32(zip, at + 24),
                localOffset = u32(zip, at + 42),
                record = at,
            )
            at += 46 + nameLen + extraLen + commentLen
        }
        return Archive(out, cdOffset.toInt(), cdSize.toInt(), eocd)
    }

    /** Where an entry's data starts. */
    private fun dataStart(zip: ByteArray, e: Entry): Int {
        val at = e.localOffset.toInt()
        if (u32(zip, at) != LOCAL) throw Refused("The archive is damaged at ${e.name}.")
        return at + 30 + u16(zip, at + 26) + u16(zip, at + 28)
    }

    /** Where an entry's span ends: its data, plus a data descriptor when it has one. */
    private fun spanEnd(zip: ByteArray, e: Entry): Int {
        val end = dataStart(zip, e) + e.compressedSize.toInt()
        if (e.flags and 0x08 == 0) return end
        return end + if (end + 4 <= zip.size && u32(zip, end) == DESCRIPTOR) 16 else 12
    }

    /** An entry's uncompressed bytes. */
    fun read(zip: ByteArray, e: Entry): ByteArray {
        if (e.flags and 0x01 != 0) throw Refused("${e.name} is encrypted.")
        val start = dataStart(zip, e)
        val raw = zip.copyOfRange(start, start + e.compressedSize.toInt())
        return when (e.method) {
            0 -> raw
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(raw)
                    val out = ByteArrayOutputStream(e.size.toInt().coerceAtLeast(64))
                    val buf = ByteArray(64 * 1024)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            }
            else -> throw Refused("${e.name} uses a compression this cannot read.")
        }
    }

    fun read(zip: ByteArray, name: String): ByteArray? =
        open(zip).entries.firstOrNull { it.name == name }?.let { read(zip, it) }

    /** The archive with [replacements] (entry name to new content) swapped in. */
    fun replace(zip: ByteArray, replacements: Map<String, ByteArray>): ByteArray {
        val archive = open(zip)
        val targets = archive.entries.filter { it.name in replacements }.sortedBy { it.localOffset }
        val missing = replacements.keys - targets.map { it.name }.toSet()
        if (missing.isNotEmpty()) throw Refused("${missing.first()} is not in this archive.")

        // New span per replaced entry, and how much each shifts what follows it.
        class Swap(val entry: Entry, val start: Int, val end: Int, val bytes: ByteArray, val crc: Long, val csize: Long, val size: Long)
        val swaps = targets.map { e ->
            if (e.flags and 0x01 != 0) throw Refused("${e.name} is encrypted.")
            if (e.method != 0 && e.method != 8) throw Refused("${e.name} uses a compression this cannot write.")
            val content = replacements.getValue(e.name)
            val crc = CRC32().apply { update(content) }.value
            val data = if (e.method == 8) deflate(content) else content
            val at = e.localOffset.toInt()
            val headerLen = 30 + u16(zip, at + 26) + u16(zip, at + 28)
            val header = zip.copyOfRange(at, at + headerLen)
            // Sizes are known now, so the descriptor is dropped and they go in the header.
            put16(header, 6, u16(header, 6) and 0x08.inv())
            put32(header, 14, crc)
            put32(header, 18, data.size.toLong())
            put32(header, 22, content.size.toLong())
            Swap(e, at, spanEnd(zip, e), header + data, crc, data.size.toLong(), content.size.toLong())
        }
        for (k in 1 until swaps.size) if (swaps[k].start < swaps[k - 1].end) throw Refused("The archive's entries overlap.")
        if (swaps.isNotEmpty() && swaps.last().end > archive.cdOffset) throw Refused("The archive's directory is damaged.")

        fun shift(offset: Long): Long = swaps.filter { it.start < offset }.sumOf { (it.bytes.size - (it.end - it.start)).toLong() }

        val out = ByteArrayOutputStream(zip.size + 4096)
        var at = 0
        for (s in swaps) {
            out.write(zip, at, s.start - at)
            out.write(s.bytes)
            at = s.end
        }
        out.write(zip, at, archive.cdOffset - at)

        val newCdOffset = archive.cdOffset + shift(archive.cdOffset.toLong())
        val cd = zip.copyOfRange(archive.cdOffset, archive.cdOffset + archive.cdSize)
        for (e in archive.entries) {
            val r = e.record - archive.cdOffset
            put32(cd, r + 42, e.localOffset + shift(e.localOffset))
            swaps.firstOrNull { it.entry === e }?.let { s ->
                put16(cd, r + 8, u16(cd, r + 8) and 0x08.inv())
                put32(cd, r + 16, s.crc)
                put32(cd, r + 20, s.csize)
                put32(cd, r + 24, s.size)
            }
        }
        out.write(cd)
        // Anything between the directory and its end record, then the end record itself.
        val afterCd = archive.cdOffset + archive.cdSize
        out.write(zip, afterCd, archive.eocd - afterCd)
        val end = zip.copyOfRange(archive.eocd, zip.size)
        if (newCdOffset > 0xFFFFFFFEL) throw Refused("The edited archive would need ZIP64.")
        put32(end, 16, newCdOffset)
        out.write(end)
        return out.toByteArray()
    }

    /**
     * Prove [patched] is [original] with exactly [replacements] changed.
     *
     * The same entries in the same order; every untouched entry's compressed bytes identical;
     * every replaced entry inflating to exactly its new content; and the whole archive readable
     * end to end by the platform's own `ZipInputStream`, which checks every CRC as it goes.
     *
     * @throws Refused naming the first thing that differs.
     */
    fun check(original: ByteArray, patched: ByteArray, replacements: Map<String, ByteArray>) {
        val a = open(original)
        val b = open(patched)
        if (a.entries.map { it.name } != b.entries.map { it.name }) throw Refused("The edited archive lost or reordered entries.")
        for ((ea, eb) in a.entries.zip(b.entries)) {
            val wanted = replacements[ea.name]
            if (wanted != null) {
                if (!read(patched, eb).contentEquals(wanted)) throw Refused("${ea.name} did not hold the edit.")
            } else {
                val sa = dataStart(original, ea)
                val sb = dataStart(patched, eb)
                val same = ea.compressedSize == eb.compressedSize && ea.crc == eb.crc &&
                    original.copyOfRange(sa, sa + ea.compressedSize.toInt())
                        .contentEquals(patched.copyOfRange(sb, sb + eb.compressedSize.toInt()))
                if (!same) throw Refused("${ea.name} changed when it should not have.")
            }
        }
        ZipInputStream(ByteArrayInputStream(patched)).use { z ->
            val buf = ByteArray(64 * 1024)
            while (z.nextEntry != null) while (z.read(buf) > 0) Unit
        }
    }

    private fun deflate(content: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(content)
            deflater.finish()
            val out = ByteArrayOutputStream(content.size / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Long) {
        put16(b, at, (v and 0xFFFF).toInt())
        put16(b, at + 2, ((v shr 16) and 0xFFFF).toInt())
    }
}
