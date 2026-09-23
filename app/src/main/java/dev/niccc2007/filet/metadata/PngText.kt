package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Reading and writing PNG text chunks.
 *
 * PNG is the format worth doing first and doing properly. Its container is a flat run of
 * length-prefixed, CRC-checked chunks, so a text chunk can be inserted or replaced without any
 * other byte in the file moving - no offsets, no tables, nothing downstream to recompute. That
 * is what makes it the one format here where a custom key and an embedded image both work
 * without a trick, and it is why this is [MetadataSupport.Tier.FULL].
 *
 * Two chunk types are handled:
 *
 *  - `tEXt` - Latin-1 keyword and value. What every tool reads.
 *  - `iTXt` - UTF-8, which is what anything with a non-Latin-1 character has to use, and what
 *    an embedded image rides in as base64.
 *
 * The rules that keep a written file valid, all of which are easy to get wrong silently:
 *
 *  - **Text chunks go before `IEND` and after `IHDR`.** A chunk after `IEND` is ignored by
 *    every decoder, so the metadata appears to write and then does not exist.
 *  - **`IDAT` chunks must stay contiguous.** Inserting between them is a decoder error, so
 *    insertion happens before the first `IDAT`.
 *  - **Every chunk carries a CRC over its type and data**, not over its length. A wrong CRC
 *    makes strict decoders reject the whole file.
 *  - **Keywords are 1-79 bytes**, printable Latin-1, no leading, trailing or consecutive
 *    spaces. An invalid keyword is a chunk most readers skip, which looks like a lost write.
 */
object PngText {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** One text entry in a PNG. */
    data class Entry(val key: String, val value: String, val utf8: Boolean)

    /** Whether [bytes] starts with the PNG signature. */
    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * A PNG keyword is more constrained than it looks.
     *
     * Enforced rather than sanitised: silently rewriting somebody's key means the value cannot
     * be found again under the name they gave it.
     */
    fun isValidKeyword(key: String): Boolean {
        if (key.isEmpty() || key.length > 79) return false
        if (key.first() == ' ' || key.last() == ' ') return false
        if (key.contains("  ")) return false
        return key.all { it.code in 32..126 || it.code in 161..255 }
    }

    /** Every text chunk in the file, in the order they appear. */
    fun read(bytes: ByteArray): List<Entry> {
        if (!isPng(bytes)) return emptyList()
        val out = ArrayList<Entry>()
        walk(bytes) { type, data ->
            when (type) {
                "tEXt" -> {
                    val nul = data.indexOf(0)
                    if (nul > 0) {
                        out.add(
                            Entry(
                                String(data, 0, nul, Charsets.ISO_8859_1),
                                String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1),
                                utf8 = false,
                            )
                        )
                    }
                }
                "iTXt" -> parseITxt(data)?.let { out.add(it) }
            }
            true
        }
        return out
    }

    /**
     * Return [bytes] with [entries] as its text chunks.
     *
     * Every existing text chunk is dropped and the given set written in its place, so the
     * result is exactly what was asked for rather than the union of old and new - a merge
     * would make removing an entry impossible.
     *
     * @return the new file, or null if the input is not a PNG this understands. Null rather
     *   than a partial write: the caller replaces a real file with this.
     */
    fun write(bytes: ByteArray, entries: List<Entry>): ByteArray? {
        if (!isPng(bytes)) return null
        for (e in entries) if (!isValidKeyword(e.key)) return null

        val out = ByteArrayOutputStream(bytes.size + 512)
        out.write(SIGNATURE)
        var wrote = false
        var sawIhdr = false

        val ok = walk(bytes) { type, data ->
            when {
                type == "tEXt" || type == "iTXt" -> Unit // dropped; replaced below
                else -> {
                    // Before the first IDAT and after IHDR. IDAT chunks must stay contiguous,
                    // and a text chunk after IEND is ignored by every decoder.
                    if (!wrote && sawIhdr && (type == "IDAT" || type == "IEND")) {
                        for (e in entries) out.write(chunkFor(e))
                        wrote = true
                    }
                    out.write(chunk(type, data))
                    if (type == "IHDR") sawIhdr = true
                }
            }
            true
        }
        if (!ok || !sawIhdr) return null
        // A file with no IDAT and no IEND is malformed; refuse rather than emit something.
        if (!wrote) return null
        return out.toByteArray()
    }

    /** Convenience: set or replace one key, leaving the others alone. */
    fun put(bytes: ByteArray, key: String, value: String, utf8: Boolean = true): ByteArray? {
        val existing = read(bytes).filterNot { it.key == key }
        return write(bytes, existing + Entry(key, value, utf8))
    }

    /** Convenience: remove one key. */
    fun remove(bytes: ByteArray, key: String): ByteArray? =
        write(bytes, read(bytes).filterNot { it.key == key })

    // ── chunk plumbing ──────────────────────────────────────────────────────────────────

    /**
     * Walk the chunks, stopping at the end or at the first malformed one.
     *
     * @return true if the whole file parsed cleanly.
     */
    private inline fun walk(bytes: ByteArray, onChunk: (String, ByteArray) -> Boolean): Boolean {
        var i = 8
        while (i + 8 <= bytes.size) {
            val len = readInt(bytes, i)
            // A negative or absurd length is a corrupt file, not something to trust into an
            // allocation.
            if (len < 0 || i + 12 + len > bytes.size) return false
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(i + 8, i + 8 + len)
            if (!onChunk(type, data)) return true
            i += 12 + len
            if (type == "IEND") return true
        }
        return false
    }

    private fun chunkFor(e: Entry): ByteArray =
        if (e.utf8) chunk("iTXt", iTxtData(e.key, e.value))
        else chunk("tEXt", e.key.toByteArray(Charsets.ISO_8859_1) + 0 + e.value.toByteArray(Charsets.ISO_8859_1))

    /**
     * iTXt payload: keyword, NUL, compression flag, compression method, language tag, NUL,
     * translated keyword, NUL, then UTF-8 text.
     *
     * Written uncompressed. Compression would save bytes on a long value and costs the ability
     * to read the file with anything that does not inflate, which is the wrong trade for
     * something whose whole purpose is being found later.
     */
    private fun iTxtData(key: String, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(key.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(0) // not compressed
        out.write(0) // compression method, ignored when uncompressed
        out.write(0) // empty language tag
        out.write(0) // empty translated keyword
        out.write(value.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun parseITxt(data: ByteArray): Entry? {
        val nul1 = data.indexOf(0)
        if (nul1 <= 0 || nul1 + 2 >= data.size) return null
        val key = String(data, 0, nul1, Charsets.ISO_8859_1)
        val compressed = data[nul1 + 1].toInt() != 0
        // A compressed value is left unread rather than guessed at. It is reported as present
        // and empty, which is honest; inventing a value would be worse.
        if (compressed) return Entry(key, "", utf8 = true)
        var p = nul1 + 3
        // language tag, then translated keyword, each NUL-terminated
        repeat(2) {
            val n = data.indexOf(0, p)
            if (n < 0) return null
            p = n + 1
        }
        if (p > data.size) return null
        return Entry(key, String(data, p, data.size - p, Charsets.UTF_8), utf8 = true)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 12)
        writeInt(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        // The CRC covers the type and the data, and NOT the length. Including the length is
        // the classic mistake and produces a file strict decoders reject outright.
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(out, crc.value.toInt())
        return out.toByteArray()
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun ByteArray.indexOf(b: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == b) return i
        return -1
    }
}
