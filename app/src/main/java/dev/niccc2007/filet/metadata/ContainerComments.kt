package dev.niccc2007.filet.metadata

import java.io.ByteArrayOutputStream

/**
 * The single-comment formats: JPEG, GIF and ZIP.
 *
 * Grouped because they share the property that puts them in [MetadataSupport.Tier.FULL]: each
 * has one place to put free text, that place is length-delimited, and nothing else in the file
 * depends on where it is. None of them supports a custom key - there is exactly one slot - and
 * saying so is the honest version of the feature rather than pretending otherwise by encoding
 * `key=value` into the one field and hoping nothing else reads it.
 */
object ContainerComments {

    // ── JPEG ────────────────────────────────────────────────────────────────────────────

    private const val SOI = 0xD8
    private const val COM = 0xFE
    private const val SOS = 0xDA
    private const val EOI = 0xD9

    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == SOI

    /** The COM segment's text, or null if there is not one. */
    fun readJpegComment(b: ByteArray): String? {
        if (!isJpeg(b)) return null
        var i = 2
        while (i + 4 <= b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return null
            val marker = b[i + 1].toInt() and 0xFF
            // Everything after the start of scan is entropy-coded image data with no segment
            // structure, so walking past it reads compressed pixels as markers.
            if (marker == SOS || marker == EOI) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            if (marker == COM) return String(b, i + 4, len - 2, Charsets.UTF_8)
            i += 2 + len
        }
        return null
    }

    /**
     * Replace - or add - the COM segment.
     *
     * Inserted straight after SOI rather than at the end, because a comment after the start of
     * scan is inside the image data and is not a comment at all.
     */
    fun writeJpegComment(b: ByteArray, comment: String): ByteArray? {
        if (!isJpeg(b)) return null
        val text = comment.toByteArray(Charsets.UTF_8)
        // The segment length field is two bytes and includes itself, so the text cannot be
        // longer than this. Refused rather than truncated: a silently cut comment is worse
        // than a refusal that says why.
        if (text.size + 2 > 0xFFFF) return null

        val out = ByteArrayOutputStream(b.size + text.size + 8)
        out.write(0xFF); out.write(SOI)
        if (comment.isNotEmpty()) {
            out.write(0xFF); out.write(COM)
            val len = text.size + 2
            out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
            out.write(text)
        }

        var i = 2
        while (i < b.size) {
            if (i + 1 >= b.size || (b[i].toInt() and 0xFF) != 0xFF) {
                // Not at a marker any more - malformed before the scan started.
                return null
            }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == SOS) {
                // From here to the end is image data; copy it untouched and stop parsing.
                out.write(b, i, b.size - i)
                return out.toByteArray()
            }
            if (marker == EOI) {
                out.write(b, i, b.size - i)
                return out.toByteArray()
            }
            if (i + 4 > b.size) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            // Drop any existing comment; everything else is copied exactly.
            if (marker != COM) out.write(b, i, 2 + len)
            i += 2 + len
        }
        return out.toByteArray()
    }

    // ── GIF ─────────────────────────────────────────────────────────────────────────────

    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && String(b, 0, 3, Charsets.US_ASCII) == "GIF"

    /**
     * Add a comment extension right after the header block.
     *
     * GIF comment extensions are sub-block chains, each at most 255 bytes, ending with a zero
     * byte. A single block longer than that is the mistake that produces a file that decodes
     * up to the comment and then stops.
     */
    fun writeGifComment(b: ByteArray, comment: String): ByteArray? {
        if (!isGif(b)) return null
        val header = gifHeaderLength(b) ?: return null
        val out = ByteArrayOutputStream(b.size + comment.length + 32)
        out.write(b, 0, header)
        if (comment.isNotEmpty()) {
            out.write(0x21) // extension introducer
            out.write(0xFE) // comment label
            val text = comment.toByteArray(Charsets.UTF_8)
            var p = 0
            while (p < text.size) {
                val n = minOf(255, text.size - p)
                out.write(n)
                out.write(text, p, n)
                p += n
            }
            out.write(0) // block terminator
        }
        // Everything after the header, minus any comment extensions already there.
        var i = header
        while (i < b.size) {
            if ((b[i].toInt() and 0xFF) == 0x21 && i + 1 < b.size && (b[i + 1].toInt() and 0xFF) == 0xFE) {
                i += 2
                while (i < b.size) {
                    val n = b[i].toInt() and 0xFF
                    i += 1 + n
                    if (n == 0) break
                }
                continue
            }
            out.write(b[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    /** Header, logical screen descriptor, and the global colour table if there is one. */
    private fun gifHeaderLength(b: ByteArray): Int? {
        if (b.size < 13) return null
        val packed = b[10].toInt() and 0xFF
        val hasTable = (packed and 0x80) != 0
        val size = if (hasTable) 3 * (1 shl ((packed and 0x07) + 1)) else 0
        val len = 13 + size
        return if (len <= b.size) len else null
    }

    // ── ZIP ─────────────────────────────────────────────────────────────────────────────

    private val EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    /**
     * The archive comment at the very end of a zip.
     *
     * Outside the central directory and outside the signed region of an APK, which is why this
     * is the one thing that can be changed on a signed package without invalidating it.
     */
    fun readZipComment(b: ByteArray): String? {
        val at = findEocd(b) ?: return null
        val len = ((b[at + 21].toInt() and 0xFF) shl 8) or (b[at + 20].toInt() and 0xFF)
        if (len == 0 || at + 22 + len > b.size) return if (len == 0) "" else null
        return String(b, at + 22, len, Charsets.UTF_8)
    }

    fun writeZipComment(b: ByteArray, comment: String): ByteArray? {
        val at = findEocd(b) ?: return null
        val text = comment.toByteArray(Charsets.UTF_8)
        // The length is a two-byte field; a longer comment cannot be recorded at all.
        if (text.size > 0xFFFF) return null
        val out = ByteArrayOutputStream(at + 22 + text.size)
        out.write(b, 0, at + 20)
        out.write(text.size and 0xFF)
        out.write((text.size ushr 8) and 0xFF)
        out.write(text)
        return out.toByteArray()
    }

    /**
     * Find the end-of-central-directory record.
     *
     * Searched backwards from the end, because the signature can legitimately occur inside
     * compressed data and the LAST one is the real record. Only the final 64 KB plus the
     * record itself can hold it, which is the size of the comment length field.
     */
    private fun findEocd(b: ByteArray): Int? {
        if (b.size < 22) return null
        val lowest = maxOf(0, b.size - 22 - 0xFFFF)
        var i = b.size - 22
        while (i >= lowest) {
            if (b[i] == EOCD[0] && b[i + 1] == EOCD[1] && b[i + 2] == EOCD[2] && b[i + 3] == EOCD[3]) {
                return i
            }
            i--
        }
        return null
    }
}
