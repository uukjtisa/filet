package dev.niccc2007.filet.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MP4 writer, and mostly the one thing that makes it dangerous.
 *
 * Tags live in `moov`, and the sample tables inside `moov` point at the media with absolute file
 * offsets. Growing the tag moves the media and leaves every offset short. The fix is to add the
 * same delta to each entry - but only to the ones that actually moved, which is what most of this
 * file is about. A file with `mdat` in front of `moov` is the common case on a phone, and adding
 * the delta to its offsets as well is how a tagger silently breaks one.
 */
class Mp4TagsTest {

    private fun be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun atom(type: String, payload: ByteArray): ByteArray =
        be(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun ftyp(): ByteArray = atom("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + be(512))

    /** Marker bytes, so a wrong offset is visible as the wrong value rather than as a crash. */
    private val media = ByteArray(64) { it.toByte() }

    private fun stco(vararg offsets: Int): ByteArray =
        atom("stco", be(0) + be(offsets.size) + offsets.fold(ByteArray(0)) { a, o -> a + be(o) })

    private fun co64(vararg offsets: Long): ByteArray =
        atom(
            "co64",
            be(0) + be(offsets.size) + offsets.fold(ByteArray(0)) { a, o ->
                a + be((o ushr 32).toInt()) + be(o.toInt())
            },
        )

    private fun moov(table: ByteArray): ByteArray =
        atom("moov", atom("trak", atom("mdia", atom("minf", atom("stbl", table)))))

    /**
     * A file with `moov` in front of `mdat`, which is the shape a tag edit moves things in.
     *
     * The offsets are worked out from the layout rather than guessed: ftyp is 16 bytes, the moov
     * chain is five nested headers plus the table, and the media starts eight bytes into mdat.
     */
    private fun moovFirst(): ByteArray {
        val chainOverhead = 8 * 5 // moov, trak, mdia, minf, stbl
        val tableSize = 8 + 8 + 4 * 2 // stco header, version and count, two entries
        val mediaAt = 16 + chainOverhead + tableSize + 8
        val file = ftyp() + moov(stco(mediaAt, mediaAt + 32)) + atom("mdat", media)
        // If the arithmetic above is wrong every assertion below is meaningless, so it is checked.
        assertEquals("the layout arithmetic is wrong", 0.toByte(), file[mediaAt])
        assertEquals(32.toByte(), file[mediaAt + 32])
        return file
    }

    /** The other order, where a tag edit must move nothing at all. */
    private fun mdatFirst(): ByteArray {
        val mediaAt = 16 + 8
        val file = ftyp() + atom("mdat", media) + moov(stco(mediaAt, mediaAt + 32))
        assertEquals(0.toByte(), file[mediaAt])
        return file
    }

    /** Read the chunk offsets back out, found by searching for the table rather than by parsing. */
    private fun offsetsIn(file: ByteArray, type: String = "stco"): List<Long> {
        val tag = type.toByteArray(Charsets.ISO_8859_1)
        var at = -1
        for (i in 0 until file.size - 4) {
            if (file[i] == tag[0] && file[i + 1] == tag[1] && file[i + 2] == tag[2] && file[i + 3] == tag[3]) {
                at = i + 4
                break
            }
        }
        if (at < 0) return emptyList()
        fun u32(p: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (file[p + i].toLong() and 0xFF)
            return v
        }
        val count = u32(at + 4).toInt()
        val wide = type == "co64"
        return (0 until count).map { i ->
            if (wide) {
                (u32(at + 8 + i * 8) shl 32) or u32(at + 12 + i * 8)
            } else {
                u32(at + 8 + i * 4)
            }
        }
    }

    @Test
    fun `a synthetic file is recognised and starts untagged`() {
        val file = moovFirst()
        assertTrue(Mp4Tags.isMp4(file))
        assertTrue(Mp4Tags.read(file).isEmpty())
        assertNull(Mp4Tags.picture(file))
    }

    @Test
    fun `a title can be added to a file that has no tag chain at all`() {
        // An untagged file straight out of an encoder has no udta, let alone a meta or an ilst.
        // A writer that can only edit an existing chain can never add the first tag to anything.
        val out = Mp4Tags.put(moovFirst(), "Title", "first light")!!
        assertEquals(listOf("Title" to "first light"), Mp4Tags.read(out))
    }

    @Test
    fun `the media still starts where the offsets say it does`() {
        // The test this file exists for. moov grows, mdat moves, and the offsets have to follow.
        val out = Mp4Tags.put(moovFirst(), "Title", "a title long enough to move things")!!
        val offsets = offsetsIn(out)
        assertEquals(2, offsets.size)
        assertEquals("the first chunk offset no longer points at the first chunk", 0.toByte(), out[offsets[0].toInt()])
        assertEquals(32.toByte(), out[offsets[1].toInt()])
        // And the media itself is byte for byte what it was.
        assertArrayEquals(media, out.copyOfRange(offsets[0].toInt(), offsets[0].toInt() + media.size))
    }

    @Test
    fun `offsets in front of moov are left exactly alone`() {
        // The case a blanket add breaks. Nothing before moov moved, so adding the delta to these
        // would point them past the data they describe - into the tag that was just written.
        val file = mdatFirst()
        val before = offsetsIn(file)
        val out = Mp4Tags.put(file, "Title", "a title long enough to move things")!!
        val after = offsetsIn(out)
        assertEquals(before, after)
        assertEquals(0.toByte(), out[after[0].toInt()])
        assertEquals(32.toByte(), out[after[1].toInt()])
    }

    @Test
    fun `a 64-bit offset table is corrected too`() {
        // A file over four gigabytes uses co64 instead, and a writer that only knows stco
        // silently leaves the big ones wrong.
        val chainOverhead = 8 * 5
        val tableSize = 8 + 8 + 8 * 2
        val mediaAt = 16 + chainOverhead + tableSize + 8
        val file = ftyp() + moov(co64(mediaAt.toLong(), mediaAt + 32L)) + atom("mdat", media)
        assertEquals(0.toByte(), file[mediaAt])
        val out = Mp4Tags.put(file, "Title", "a title long enough to move things")!!
        val offsets = offsetsIn(out, "co64")
        assertEquals(2, offsets.size)
        assertEquals(0.toByte(), out[offsets[0].toInt()])
        assertEquals(32.toByte(), out[offsets[1].toInt()])
    }

    @Test
    fun `removing a field shrinks the file and the offsets come back`() {
        // The delta is negative, and a writer that only ever grows gets this wrong in the
        // direction that is hardest to notice.
        val file = moovFirst()
        val tagged = Mp4Tags.put(file, "Title", "a title long enough to move things")!!
        val cleared = Mp4Tags.put(tagged, "Title", "")!!
        assertTrue(cleared.size < tagged.size)
        val offsets = offsetsIn(cleared)
        assertEquals(0.toByte(), cleared[offsets[0].toInt()])
        assertEquals(32.toByte(), cleared[offsets[1].toInt()])
    }

    @Test
    fun `writing one field keeps the others`() {
        var file = Mp4Tags.put(moovFirst(), "Title", "first")!!
        file = Mp4Tags.put(file, "Artist", "someone")!!
        file = Mp4Tags.put(file, "Album", "a record")!!
        val read = Mp4Tags.read(file).toMap()
        assertEquals("first", read["Title"])
        assertEquals("someone", read["Artist"])
        assertEquals("a record", read["Album"])
    }

    @Test
    fun `a track number round trips as a pair rather than as four raw bytes`() {
        val out = Mp4Tags.put(moovFirst(), "Track", "3/12")!!
        assertEquals("3/12", Mp4Tags.read(out).toMap()["Track"])
        val alone = Mp4Tags.put(moovFirst(), "Track", "7")!!
        assertEquals("7", Mp4Tags.read(alone).toMap()["Track"])
    }

    @Test
    fun `a track number that is not a number is refused`() {
        assertNull(Mp4Tags.put(moovFirst(), "Track", "side two"))
    }

    @Test
    fun `a field this container has no slot for is refused`() {
        // Rather than written into whichever item happens to be nearest.
        assertNull(Mp4Tags.put(moovFirst(), "secret note", "x"))
    }

    @Test
    fun `a cover survives a round trip and the media stays put`() {
        val art = ByteArray(3_000) { (it % 251).toByte() }
        val out = Mp4Tags.putPicture(moovFirst(), Cover.of(art))!!
        assertArrayEquals(art, Mp4Tags.picture(out)!!.bytes)
        val offsets = offsetsIn(out)
        assertEquals(0.toByte(), out[offsets[0].toInt()])
        assertArrayEquals(media, out.copyOfRange(offsets[0].toInt(), offsets[0].toInt() + media.size))
    }

    @Test
    fun `a cover can be removed without losing the words`() {
        val tagged = Mp4Tags.put(moovFirst(), "Title", "keeps its name")!!
        val withArt = Mp4Tags.putPicture(tagged, Cover.of(ByteArray(80) { 5 }))!!
        assertNotNull(Mp4Tags.picture(withArt))
        val stripped = Mp4Tags.putPicture(withArt, null)!!
        assertNull(Mp4Tags.picture(stripped))
        assertEquals("keeps its name", Mp4Tags.read(stripped).toMap()["Title"])
    }

    @Test
    fun `a fragmented file is refused`() {
        // Its offsets live in moof and sidx boxes outside moov, which this does not touch - and
        // a video that plays from the start and stops at the first seek is the worst outcome.
        val file = ftyp() + moov(stco(0)) + atom("moof", ByteArray(8)) + atom("mdat", media)
        assertTrue(Mp4Tags.isFragmented(file))
        assertNull(Mp4Tags.put(file, "Title", "t"))
        assertNull(Mp4Tags.putPicture(file, Cover.of(ByteArray(4))))
    }

    @Test
    fun `a 64-bit atom header is read rather than misread as an empty one`() {
        // A size of 1 means the real size follows the type. Reading it as the number one walks
        // the parser one byte at a time through the whole file.
        val media64 = be(1) + "mdat".toByteArray(Charsets.ISO_8859_1) +
            ByteArray(8) { if (it == 7) (16 + media.size).toByte() else 0 } + media
        val file = ftyp() + media64
        val found = Mp4Tags.atoms(file, 0, file.size)
        assertEquals(listOf("ftyp", "mdat"), found.map { it.type })
        assertEquals(16 + media.size, found[1].size)
    }

    @Test
    fun `an atom declaring zero runs to the end of the file`() {
        val file = ftyp() + be(0) + "mdat".toByteArray(Charsets.ISO_8859_1) + media
        val found = Mp4Tags.atoms(file, 0, file.size)
        assertEquals(2, found.size)
        assertEquals(file.size, found[1].end)
    }

    @Test
    fun `a QuickTime meta without version flags is still read`() {
        // `meta` is a full box in the ISO format and a plain container in QuickTime, and files of
        // both kinds are called .m4a. Guessing from the extension gets one of them wrong.
        val data = be(1) + be(0) + "quicktime".toByteArray(Charsets.UTF_8)
        val item = atom("©nam", atom("data", data))
        val quicktime = ftyp() +
            atom("moov", atom("udta", atom("meta", atom("hdlr", ByteArray(20)) + atom("ilst", item)))) +
            atom("mdat", media)
        assertEquals(listOf("Title" to "quicktime"), Mp4Tags.read(quicktime))
    }

    @Test
    fun `something that is not an MP4 is refused quietly`() {
        assertTrue(!Mp4Tags.isMp4(ByteArray(64)))
        assertNull(Mp4Tags.put(ByteArray(64), "Title", "t"))
        assertTrue(Mp4Tags.read(ByteArray(64)).isEmpty())
    }
}
