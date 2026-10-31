package dev.niccc2007.filet.media

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Which way up a JPEG is, read from and written to its EXIF block.
 *
 * ## The fault this exists for
 *
 * Bug identified here: a camera that is held sideways often does not turn the pixels. It
 * stores them as the sensor saw them and writes one number - EXIF tag 0x0112, Orientation -
 * saying how to turn them for display. `BitmapFactory` ignores that tag, and every picture this
 * app decoded went through `BitmapFactory`, so a portrait photo from such a camera showed lying
 * on its side in the viewer and in every thumbnail, while the gallery showed it upright.
 *
 * ## Why turning a photo writes the tag rather than the pixels
 *
 * Turning the pixels means decoding the photo and encoding it again, and a JPEG encode is
 * lossy: every save loses a little, and a photo turned four times to get back where it started
 * has been degraded four times for nothing. Changing the one number is lossless, instant and
 * reversible, and it is what the camera itself did - so a photo turned here is exactly the file
 * it would have been had the camera been held that way. The pixels, the date, the place, the
 * camera details and the embedded thumbnail are all left as they were.
 *
 * Read and written here rather than through `ExifInterface`, because `ExifInterface` writes only
 * to a file it can seek in, and this app's files are as often on a share or in a document
 * provider as on the phone. This works on bytes, and it is pure, so every case below is tested
 * off the device.
 *
 * ## The three shapes a JPEG can be in
 *
 * 1. **It has the tag.** The value is overwritten in place - two bytes - and nothing moves.
 * 2. **It has no EXIF block at all.** A minimal one, holding only the orientation, is put after
 *    the start of the file (after a JFIF header, which must come first).
 * 3. **It has an EXIF block without the tag.** Adding an entry to the first directory would move
 *    everything after it, and EXIF is full of absolute offsets - including inside the maker's
 *    own notes, which nothing outside the maker can rewrite. So the directory is COPIED, with
 *    the new entry, to the end of the block, and the one pointer to it is changed. Every other
 *    offset still points where it pointed, because nothing it points at moved. The old directory
 *    is left behind as unreferenced bytes.
 *
 * Anything else - a block that does not parse, a segment that would outgrow its 64 KB limit -
 * is refused rather than guessed at, and the caller says the picture cannot be turned in place.
 */
object JpegOrientation {

    /** Upright: no turn, no mirror. Also what anything without a tag is. */
    const val NORMAL = 1

    /** The orientation tag, and the two value types a writer has been seen to use for it. */
    private const val TAG = 0x0112
    private const val SHORT = 3
    private const val LONG = 4

    /** A file whose EXIF block has not started within this much is not going to have one. */
    private const val HEAD_LIMIT = 256 * 1024

    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2

    private val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val MPF_ID = byteArrayOf(0x4D, 0x50, 0x46, 0) // "MPF\0"

    /** How the stored pixels are turned for display: mirrored left to right FIRST, then turned. */
    data class Transform(val mirrored: Boolean, val degreesClockwise: Int) {
        /** Whether the displayed picture is the stored one on its side: width and height swap. */
        val sideways: Boolean get() = degreesClockwise % 180 != 0
    }

    /** Whether these bytes start like a JPEG. */
    fun isJpeg(head: ByteArray): Boolean =
        head.size >= 3 && u8(head, 0) == 0xFF && u8(head, 1) == SOI && u8(head, 2) == 0xFF

    /**
     * The front of [input], up to and including its EXIF block - or as far as there is no point
     * looking. What was read is returned whole, so the caller can put it back in front of the
     * rest of the stream and decode the picture without opening the file a second time.
     */
    fun head(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        fun take(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            out.write(buf, 0, got)
            return if (got == n) buf else null
        }
        val soi = take(2) ?: return out.toByteArray()
        if (u8(soi, 0) != 0xFF || u8(soi, 1) != SOI) return out.toByteArray()
        while (out.size() < HEAD_LIMIT) {
            val marker = take(2) ?: break
            if (u8(marker, 0) != 0xFF) break
            val kind = u8(marker, 1)
            if (kind == SOS || kind == EOI) break
            val len = take(2) ?: break
            val length = (u8(len, 0) shl 8) or u8(len, 1)
            if (length < 2) break
            val body = take(length - 2) ?: break
            if (kind == APP1 && startsWith(body, 0, EXIF_ID)) break
        }
        return out.toByteArray()
    }

    /** The orientation value, 1 to 8. [NORMAL] for anything without a readable tag. */
    fun read(bytes: ByteArray): Int {
        val exif = findExif(bytes) ?: return NORMAL
        val entry = findEntry(bytes, exif) ?: return NORMAL
        val value = when (entry.type) {
            SHORT -> u16(bytes, entry.at + 8, exif.little)
            LONG -> u32(bytes, entry.at + 8, exif.little).toInt()
            else -> return NORMAL
        }
        return if (value in 1..8) value else NORMAL
    }

    /** What [orientation] asks the viewer to do with the stored pixels. */
    fun transformOf(orientation: Int): Transform = when (orientation) {
        2 -> Transform(true, 0)
        3 -> Transform(false, 180)
        4 -> Transform(true, 180)
        5 -> Transform(true, 270)
        6 -> Transform(false, 90)
        7 -> Transform(true, 90)
        8 -> Transform(false, 270)
        else -> Transform(false, 0)
    }

    /** The orientation that shows a picture now at [orientation] turned a further [quarters] clockwise. */
    fun turned(orientation: Int, quarters: Int): Int {
        val now = transformOf(orientation)
        val degrees = ((now.degreesClockwise + quarters * 90) % 360 + 360) % 360
        return valueOf(now.mirrored, degrees)
    }

    private fun valueOf(mirrored: Boolean, degrees: Int): Int = when {
        !mirrored && degrees == 90 -> 6
        !mirrored && degrees == 180 -> 3
        !mirrored && degrees == 270 -> 8
        mirrored && degrees == 0 -> 2
        mirrored && degrees == 90 -> 7
        mirrored && degrees == 180 -> 4
        mirrored && degrees == 270 -> 5
        else -> NORMAL
    }

    /**
     * [bytes] with its orientation set to [orientation], every pixel untouched. Null when the file
     * is not a JPEG this can change safely; see the class notes for which those are.
     */
    fun withOrientation(bytes: ByteArray, orientation: Int): ByteArray? {
        require(orientation in 1..8) { "orientation $orientation" }
        if (!isJpeg(bytes)) return null
        val segments = segments(bytes) ?: return null
        val exifSeg = segments.firstOrNull { it.kind == APP1 && startsWith(bytes, it.body, EXIF_ID) }
        // A multi-picture index (the depth map or second frame some cameras carry after the main
        // picture) counts its offsets from its own header. Growing a segment that comes BEFORE it
        // moves the header and the pictures together, and the offsets stay right; growing one
        // AFTER it moves only the pictures. Cameras write EXIF first, so this is a refusal of a
        // file somebody else rearranged, not of an ordinary photo.
        val mpf = segments.indexOfFirst { it.kind == APP2 && startsWith(bytes, it.body, MPF_ID) }
        if (exifSeg != null && mpf >= 0 && mpf < segments.indexOf(exifSeg)) return null

        val out = when {
            exifSeg == null -> insertExif(bytes, segments, orientation)
            else -> {
                val exif = parseExif(bytes, exifSeg) ?: return null
                val entry = findEntry(bytes, exif)
                when {
                    entry != null && entry.type == SHORT && entry.count == 1L -> bytes.copyOf().also {
                        put16(it, entry.at + 8, orientation, exif.little)
                    }
                    entry != null && entry.type == LONG && entry.count == 1L -> bytes.copyOf().also {
                        put32(it, entry.at + 8, orientation.toLong(), exif.little)
                    }
                    entry != null -> return null
                    else -> appendDirectory(bytes, exifSeg, exif, orientation) ?: return null
                }
            }
        }
        patchXmp(out, orientation)
        return out
    }

    // ── segments ──

    /** One marker segment: where its marker is, what kind, and where its body runs. */
    private class Segment(val marker: Int, val kind: Int, val body: Int, val end: Int)

    /** The segments ahead of the picture data, or null if the file does not parse that far. */
    private fun segments(b: ByteArray): List<Segment>? {
        val out = ArrayList<Segment>()
        var at = 2
        while (at + 4 <= b.size) {
            if (u8(b, at) != 0xFF) return null
            val kind = u8(b, at + 1)
            if (kind == 0xFF) { at++; continue } // fill byte
            if (kind == SOS || kind == EOI) return out
            val length = (u8(b, at + 2) shl 8) or u8(b, at + 3)
            if (length < 2 || at + 2 + length > b.size) return null
            out += Segment(at, kind, at + 4, at + 2 + length)
            at += 2 + length
        }
        return null
    }

    /** Where the TIFF data inside an EXIF segment starts, which byte order it is in, and its first directory. */
    private class Exif(val segment: Segment, val tiff: Int, val little: Boolean, val ifd0: Int)

    private class Entry(val at: Int, val type: Int, val count: Long)

    private fun findExif(b: ByteArray): Exif? {
        if (!isJpeg(b)) return null
        var at = 2
        while (at + 4 <= b.size) {
            if (u8(b, at) != 0xFF) return null
            val kind = u8(b, at + 1)
            if (kind == 0xFF) { at++; continue }
            if (kind == SOS || kind == EOI) return null
            val length = (u8(b, at + 2) shl 8) or u8(b, at + 3)
            if (length < 2) return null
            val seg = Segment(at, kind, at + 4, minOf(b.size, at + 2 + length))
            if (kind == APP1 && startsWith(b, seg.body, EXIF_ID)) return parseExif(b, seg)
            at += 2 + length
        }
        return null
    }

    private fun parseExif(b: ByteArray, seg: Segment): Exif? {
        val tiff = seg.body + EXIF_ID.size
        if (tiff + 8 > seg.end) return null
        val little = when {
            u8(b, tiff) == 0x49 && u8(b, tiff + 1) == 0x49 -> true
            u8(b, tiff) == 0x4D && u8(b, tiff + 1) == 0x4D -> false
            else -> return null
        }
        if (u16(b, tiff + 2, little) != 42) return null
        val ifd0 = u32(b, tiff + 4, little)
        if (ifd0 < 8 || tiff + ifd0 + 2 > seg.end) return null
        return Exif(seg, tiff, little, ifd0.toInt())
    }

    private fun findEntry(b: ByteArray, exif: Exif): Entry? = findEntry(b, exif, exif.ifd0, TAG)

    /** The entry for [tag] in the directory at TIFF offset [ifd], or null. */
    private fun findEntry(b: ByteArray, exif: Exif, ifd: Int, tag: Int): Entry? {
        val dir = exif.tiff + ifd
        if (ifd < 8 || dir + 2 > exif.segment.end) return null
        val n = u16(b, dir, exif.little)
        if (dir + 2 + n * 12 > exif.segment.end) return null
        for (i in 0 until n) {
            val at = dir + 2 + i * 12
            if (u16(b, at, exif.little) == tag) {
                return Entry(at, u16(b, at + 2, exif.little), u32(b, at + 4, exif.little))
            }
        }
        return null
    }

    /** An entry holding one SHORT or LONG, set to [value]. False for anything else. */
    private fun setNumber(b: ByteArray, entry: Entry, value: Long, little: Boolean): Boolean = when {
        entry.count != 1L -> false
        entry.type == SHORT && value <= 0xFFFF -> { put16(b, entry.at + 8, value.toInt(), little); true }
        entry.type == LONG -> { put32(b, entry.at + 8, value, little); true }
        else -> false
    }

    // ── carrying the details across an edit ──

    /**
     * The width and height a JPEG's frame header gives, or null when it does not parse that far.
     */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (!isJpeg(bytes)) return null
        val sof = segments(bytes)?.firstOrNull { it.kind in SOF_KINDS } ?: return null
        if (sof.body + 5 > sof.end) return null
        val height = (u8(bytes, sof.body + 1) shl 8) or u8(bytes, sof.body + 2)
        val width = (u8(bytes, sof.body + 3) shl 8) or u8(bytes, sof.body + 4)
        return width to height
    }

    /**
     * [into] - a JPEG just encoded from an edit - carrying the EXIF block of [from], the photo it
     * was edited from. Null when there is nothing to carry or it cannot be done safely.
     *
     * Bug identified here: `Bitmap.compress` writes pixels and nothing else, so every edited photo
     * lost when it was taken, where, and on what. A gallery sorts by the date taken, so a photo
     * cropped and saved over itself jumped from last summer to today, and a copy saved beside it
     * stood apart from the rest of its day.
     *
     * The block is copied whole, with three things changed because they described the old
     * pixels rather than the photo: the orientation is set upright, since the edit was made on
     * the upright picture and the turn is in its pixels now; the embedded thumbnail is unlinked,
     * because it shows the picture before the edit and some galleries draw it in their grids; and
     * the pixel dimensions are set to the new size. [from] needs only to reach the end of its
     * EXIF block - [head] is enough.
     */
    fun carried(from: ByteArray, into: ByteArray): ByteArray? {
        if (!isJpeg(into)) return null
        val source = findExif(from) ?: return null
        if (source.segment.end - source.segment.marker != segmentLength(from, source.segment)) return null
        val shift = source.segment.marker
        val block = from.copyOfRange(shift, source.segment.end)
        val seg = Segment(0, APP1, source.segment.body - shift, block.size)
        val exif = Exif(seg, source.tiff - shift, source.little, source.ifd0)
        val little = exif.little

        findEntry(block, exif)?.let { setNumber(block, it, NORMAL.toLong(), little) }
        // Unlink the second directory - the thumbnail of the picture as it was.
        val dir = exif.tiff + exif.ifd0
        val n = u16(block, dir, little)
        val next = dir + 2 + n * 12
        if (next + 4 <= block.size) put32(block, next, 0L, little)
        size(into)?.let { (w, h) ->
            val sub = findEntry(block, exif, exif.ifd0, EXIF_IFD)
            if (sub != null && sub.count == 1L && sub.type == LONG) {
                val at = u32(block, sub.at + 8, little).toInt()
                findEntry(block, exif, at, PIXEL_X)?.let { setNumber(block, it, w.toLong(), little) }
                findEntry(block, exif, at, PIXEL_Y)?.let { setNumber(block, it, h.toLong(), little) }
            }
        }

        val segments = segments(into) ?: return null
        if (segments.any { it.kind == APP1 && startsWith(into, it.body, EXIF_ID) }) return null
        val at = segments.takeWhile { it.kind == APP0 }.lastOrNull()?.end ?: 2
        return into.copyOfRange(0, at) + block + into.copyOfRange(at, into.size)
    }

    /** The length a segment's own header claims, so a block cut short by [head] is not carried. */
    private fun segmentLength(b: ByteArray, seg: Segment): Int =
        2 + ((u8(b, seg.marker + 2) shl 8) or u8(b, seg.marker + 3))

    private const val EXIF_IFD = 0x8769
    private const val PIXEL_X = 0xA002
    private const val PIXEL_Y = 0xA003

    /** Start-of-frame markers: C0 to CF, less the three that share the range and are not frames. */
    private val SOF_KINDS = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

    /** Shape 2 in the class notes: no EXIF block, so a minimal one goes in. */
    private fun insertExif(b: ByteArray, segments: List<Segment>, orientation: Int): ByteArray {
        // Big-endian TIFF: header, one directory with one entry, no next directory.
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0, 42, 0, 0, 0, 8,
            0, 1,
            0x01, 0x12, 0, SHORT.toByte(), 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        val length = 2 + EXIF_ID.size + tiff.size
        val segment = byteArrayOf(0xFF.toByte(), APP1.toByte(), (length shr 8).toByte(), length.toByte()) +
            EXIF_ID + tiff
        // After a JFIF header if there is one: JFIF says it must be the first segment.
        val at = segments.takeWhile { it.kind == APP0 }.lastOrNull()?.end ?: 2
        return b.copyOfRange(0, at) + segment + b.copyOfRange(at, b.size)
    }

    /** Shape 3 in the class notes: the first directory copied, with the entry, to the end of the block. */
    private fun appendDirectory(b: ByteArray, seg: Segment, exif: Exif, orientation: Int): ByteArray? {
        val little = exif.little
        val dir = exif.tiff + exif.ifd0
        val n = u16(b, dir, little)
        if (dir + 2 + n * 12 + 4 > seg.end) return null
        val next = u32(b, dir + 2 + n * 12, little)

        // TIFF wants a directory on an even offset.
        val pad = (seg.end - exif.tiff) and 1
        val added = pad + 2 + (n + 1) * 12 + 4
        val length = seg.end - seg.marker - 2 + added
        if (length > 0xFFFF) return null
        val offset = seg.end - exif.tiff + pad

        val copy = ByteArray(added)
        var w = pad
        put16(copy, w, n + 1, little); w += 2
        var placed = false
        for (i in 0 until n) {
            val at = dir + 2 + i * 12
            // Entries are kept in ascending tag order, as the format requires.
            if (!placed && u16(b, at, little) > TAG) {
                writeEntry(copy, w, orientation, little); w += 12; placed = true
            }
            System.arraycopy(b, at, copy, w, 12); w += 12
        }
        if (!placed) { writeEntry(copy, w, orientation, little); w += 12 }
        put32(copy, w, next, little)

        val out = b.copyOfRange(0, seg.end) + copy + b.copyOfRange(seg.end, b.size)
        out[seg.marker + 2] = (length shr 8).toByte()
        out[seg.marker + 3] = length.toByte()
        put32(out, exif.tiff + 4, offset.toLong(), little)
        return out
    }

    private fun writeEntry(into: ByteArray, at: Int, orientation: Int, little: Boolean) {
        put16(into, at, TAG, little)
        put16(into, at + 2, SHORT, little)
        put32(into, at + 4, 1L, little)
        put32(into, at + 8, 0L, little)
        put16(into, at + 8, orientation, little)
    }

    /**
     * An XMP block's copy of the orientation, changed to match where it has one.
     *
     * Editing software writes the value twice, once in EXIF and once in XMP, and an app that reads
     * the XMP one would otherwise show the old turn. One digit, overwritten in place, so nothing
     * moves; a block without one is left alone.
     */
    private fun patchXmp(b: ByteArray, orientation: Int) {
        val segments = segments(b) ?: return
        val xmp = segments.firstOrNull { it.kind == APP1 && startsWith(b, it.body, XMP_ID) } ?: return
        for (needle in XMP_KEYS) {
            var at = indexOf(b, needle, xmp.body, xmp.end)
            while (at >= 0) {
                val digit = at + needle.size
                if (digit < xmp.end && b[digit].toInt() in '1'.code..'8'.code) {
                    b[digit] = ('0'.code + orientation).toByte()
                }
                at = indexOf(b, needle, at + needle.size, xmp.end)
            }
        }
    }

    private val XMP_KEYS = listOf(
        "tiff:Orientation=\"".toByteArray(Charsets.US_ASCII),
        "tiff:Orientation>".toByteArray(Charsets.US_ASCII),
    )

    // ── bytes ──

    private fun u8(b: ByteArray, at: Int) = b[at].toInt() and 0xFF

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int =
        if (little) u8(b, at) or (u8(b, at + 1) shl 8) else (u8(b, at) shl 8) or u8(b, at + 1)

    private fun u32(b: ByteArray, at: Int, little: Boolean): Long =
        if (little) {
            u8(b, at).toLong() or (u8(b, at + 1).toLong() shl 8) or
                (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)
        } else {
            (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or
                (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
        }

    private fun put16(b: ByteArray, at: Int, v: Int, little: Boolean) {
        if (little) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() } else { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }
    }

    private fun put32(b: ByteArray, at: Int, v: Long, little: Boolean) {
        for (i in 0 until 4) {
            val shift = if (little) 8 * i else 8 * (3 - i)
            b[at + i] = (v shr shift).toByte()
        }
    }

    private fun startsWith(b: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at < 0 || at + prefix.size > b.size) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i]) return false
        return true
    }

    private fun indexOf(b: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        var i = from
        while (i + needle.size <= until) {
            if (startsWith(b, i, needle)) return i
            i++
        }
        return -1
    }
}
