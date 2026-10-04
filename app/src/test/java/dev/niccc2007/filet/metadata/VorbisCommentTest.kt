package dev.niccc2007.filet.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VorbisCommentTest {

    // ── the tag payload, which FLAC and Ogg share ─────────────────────────────────────────

    @Test
    fun `a tag survives a round trip`() {
        val tag = VorbisComment.Tag(
            "Filet",
            listOf("TITLE" to "first light", "ARTIST" to "someone"),
        )
        val back = VorbisComment.parse(VorbisComment.build(tag))
        assertEquals("Filet", back.vendor)
        assertEquals(tag.fields, back.fields)
    }

    @Test
    fun `comment lengths are little-endian, unlike everything else in FLAC`() {
        // Reading them the other way round gives a vendor string several gigabytes long, which
        // is the first thing to check when a FLAC parser appears to be reading garbage.
        val built = VorbisComment.build(VorbisComment.Tag("ab", emptyList()))
        assertEquals(2, built[0].toInt())
        assertEquals(0, built[1].toInt())
        assertEquals(0, built[2].toInt())
        assertEquals(0, built[3].toInt())
    }

    @Test
    fun `an unknown field keeps its own name rather than being hidden`() {
        // Vorbis comments are arbitrary by specification, and a reader that only shows the eight
        // it has heard of hides most of what a ripper writes.
        val tag = VorbisComment.Tag("", listOf("REPLAYGAIN_TRACK_GAIN" to "-6.2 dB"))
        assertEquals(listOf("REPLAYGAIN_TRACK_GAIN" to "-6.2 dB"), VorbisComment.readable(tag))
    }

    @Test
    fun `the base64 picture field is not shown as a line of text`() {
        // It is several hundred kilobytes of base64, and showing it in a text field is how a
        // metadata editor locks up on an album with cover art.
        val tag = VorbisComment.Tag(
            "",
            listOf("TITLE" to "t", VorbisComment.PICTURE_FIELD to "AAAA"),
        )
        assertEquals(listOf("Title" to "t"), VorbisComment.readable(tag))
    }

    @Test
    fun `a field name containing the separator is refused`() {
        // It would be written into a `KEY=value` line unescaped and read back as a different
        // field with a different value - a silent corruption rather than a failure.
        assertTrue(VorbisComment.isValidName("MOOD"))
        assertTrue(!VorbisComment.isValidName("A=B"))
        assertTrue(!VorbisComment.isValidName(""))
    }

    // ── FLAC ──────────────────────────────────────────────────────────────────────────────

    /** The audio frames, which every write has to copy through untouched. */
    private val frames = byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + ByteArray(200) { it.toByte() }

    /** STREAMINFO is 34 bytes and its contents do not matter to anything tested here. */
    private fun flac(vararg extra: VorbisComment.Block): ByteArray {
        val blocks = listOf(VorbisComment.Block(VorbisComment.STREAMINFO, false, ByteArray(34))) +
            extra.toList()
        return VorbisComment.rebuildFlac(
            "fLaC".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x80.toByte(), 0, 0, 0) + frames,
            blocks,
        )!!
    }

    @Test
    fun `a FLAC title survives a round trip and the audio is untouched`() {
        val base = flac()
        val out = VorbisComment.putFlac(base, "Title", "first light")!!
        assertEquals(listOf("Title" to "first light"), VorbisComment.readFlac(out))
        assertArrayEquals(frames, out.copyOfRange(VorbisComment.flacAudioStart(out), out.size))
    }

    @Test
    fun `STREAMINFO is forced back to the front`() {
        // A FLAC with it anywhere else does not decode, and a block list is easy to build in
        // the wrong order.
        val out = VorbisComment.rebuildFlac(
            flac(),
            listOf(
                VorbisComment.Block(VorbisComment.VORBIS_COMMENT, false, VorbisComment.build(VorbisComment.Tag("", emptyList()))),
                VorbisComment.Block(VorbisComment.STREAMINFO, false, ByteArray(34)),
            ),
        )!!
        assertEquals(VorbisComment.STREAMINFO, VorbisComment.flacBlocks(out).first().type)
    }

    @Test
    fun `the last block is the only one flagged last`() {
        // The flag is how a decoder knows where the audio starts. Two of them, or none, and it
        // reads the audio as another block header.
        val out = VorbisComment.putFlac(flac(), "Title", "t")!!
        val blocks = VorbisComment.flacBlocks(out)
        assertEquals(1, blocks.count { it.last })
        assertTrue(blocks.last().last)
    }

    @Test
    fun `padding is dropped rather than carried along`() {
        val base = flac(VorbisComment.Block(VorbisComment.PADDING, false, ByteArray(512)))
        val out = VorbisComment.putFlac(base, "Title", "t")!!
        assertTrue(VorbisComment.flacBlocks(out).none { it.type == VorbisComment.PADDING })
    }

    @Test
    fun `a FLAC picture survives a round trip`() {
        val art = ByteArray(5_000) { (it % 251).toByte() }
        val out = VorbisComment.putFlacPicture(
            flac(),
            Cover("image/jpeg", Cover.FRONT, "front", art),
        )!!
        val back = VorbisComment.flacPicture(out)
        assertNotNull(back)
        assertEquals(Cover.FRONT, back!!.kind)
        assertEquals("front", back.description)
        assertArrayEquals(art, back.bytes)
        assertArrayEquals(frames, out.copyOfRange(VorbisComment.flacAudioStart(out), out.size))
    }

    @Test
    fun `a picture can be removed without losing the words`() {
        val withBoth = VorbisComment.putFlacPicture(
            VorbisComment.putFlac(flac(), "Title", "keeps its name")!!,
            Cover("image/png", Cover.FRONT, "", ByteArray(64) { 9 }),
        )!!
        assertNotNull(VorbisComment.flacPicture(withBoth))
        val stripped = VorbisComment.putFlacPicture(withBoth, null)!!
        assertNull(VorbisComment.flacPicture(stripped))
        assertEquals("keeps its name", VorbisComment.readFlac(stripped).toMap()["Title"])
    }

    @Test
    fun `a picture written by an Ogg-shaped tagger is still found`() {
        // Some tools put the base64 field in a FLAC instead of a real PICTURE block. Reading
        // only the block means telling somebody their cover art is missing when it is there.
        val cover = Cover("image/jpeg", Cover.FRONT, "", ByteArray(40) { 3 })
        val field = java.util.Base64.getEncoder()
            .encodeToString(VorbisComment.buildPictureBlock(cover))
        val base = flac(
            VorbisComment.Block(
                VorbisComment.VORBIS_COMMENT,
                false,
                VorbisComment.build(VorbisComment.Tag("", listOf(VorbisComment.PICTURE_FIELD to field))),
            ),
        )
        assertEquals(cover.bytes.size, VorbisComment.flacPicture(base)?.bytes?.size)
    }

    // ── Ogg ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the page checksum is the one Ogg uses and not the familiar one`() {
        // Independently sourced rather than self-consistent: the CRC catalogue's check value for
        // poly 0x04C11DB7 with init 0 and no reflection is the CKSUM entry's 0x765E7680 before
        // its final inversion, so without that inversion it is the complement.
        val check = VorbisComment.pageCrc("123456789".toByteArray(Charsets.ISO_8859_1), 0, 9)
        assertEquals(0x765E7680.toInt() xor -1, check)
    }

    private val serial = 0x5A5A1234

    /** One Ogg page, built here rather than by the code under test. */
    private fun page(seq: Int, flags: Int, granule: Long, packet: ByteArray): ByteArray {
        val lacing = ArrayList<Int>()
        var n = packet.size
        while (n >= 255) {
            lacing.add(255)
            n -= 255
        }
        lacing.add(n)
        val p = ByteArray(27 + lacing.size + packet.size)
        "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(p)
        p[5] = flags.toByte()
        for (i in 0 until 8) p[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        fun le(at: Int, v: Int) {
            p[at] = (v and 0xFF).toByte()
            p[at + 1] = ((v ushr 8) and 0xFF).toByte()
            p[at + 2] = ((v ushr 16) and 0xFF).toByte()
            p[at + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        le(14, serial)
        le(18, seq)
        p[26] = lacing.size.toByte()
        for ((i, v) in lacing.withIndex()) p[27 + i] = v.toByte()
        packet.copyInto(p, 27 + lacing.size)
        le(22, VorbisComment.pageCrc(p, 0, p.size))
        return p
    }

    private fun opusHead(): ByteArray =
        "OpusHead".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    private fun opusTags(tag: VorbisComment.Tag): ByteArray =
        "OpusTags".toByteArray(Charsets.ISO_8859_1) + VorbisComment.build(tag)

    private val audioPacket = ByteArray(300) { (it % 97).toByte() }

    private fun opusFile(tag: VorbisComment.Tag = VorbisComment.Tag("ref", emptyList())): ByteArray =
        page(0, 0x02, 0L, opusHead()) +
            page(1, 0x00, 0L, opusTags(tag)) +
            page(2, 0x04, 960L, audioPacket)

    @Test
    fun `an Opus file reads its tag`() {
        val file = opusFile(VorbisComment.Tag("ref", listOf("TITLE" to "a quiet one")))
        assertEquals(listOf("Title" to "a quiet one"), VorbisComment.readOgg(file))
    }

    @Test
    fun `writing an Opus tag keeps the audio page byte for byte`() {
        // The property the whole repagination exists to preserve. The headers are rebuilt; the
        // audio is copied, and only its sequence number and checksum may differ.
        val file = opusFile()
        val out = VorbisComment.putOgg(file, "Title", "a much longer title than fitted before")!!
        assertEquals("a much longer title than fitted before", VorbisComment.readOgg(out).toMap()["Title"])

        val pages = VorbisComment.pages(out)
        val last = pages.last()
        assertArrayEquals(
            audioPacket,
            out.copyOfRange(last.bodyAt, last.bodyAt + last.bodyLen),
        )
        assertEquals(960L, last.granule)
        assertEquals(0x04, last.flags)
    }

    @Test
    fun `every rewritten page numbers and checksums correctly`() {
        // A gap in the sequence or a stale checksum is a stream players reject with no
        // explanation, and neither is visible by looking at the bytes.
        val out = VorbisComment.putOgg(opusFile(), "Artist", "x".repeat(900))!!
        val pages = VorbisComment.pages(out)
        assertEquals(pages.indices.toList(), pages.map { it.seq })
        for (p in pages) {
            val copy = out.copyOfRange(p.at, p.end)
            val stored = (copy[22].toInt() and 0xFF) or ((copy[23].toInt() and 0xFF) shl 8) or
                ((copy[24].toInt() and 0xFF) shl 16) or ((copy[25].toInt() and 0xFF) shl 24)
            for (i in 22..25) copy[i] = 0
            assertEquals("page ${p.seq} has a stale checksum", stored, VorbisComment.pageCrc(copy, 0, copy.size))
        }
    }

    @Test
    fun `the first page still carries the beginning-of-stream flag and nothing else`() {
        val out = VorbisComment.putOgg(opusFile(), "Title", "t")!!
        val first = VorbisComment.pages(out).first()
        assertEquals(0x02, first.flags)
        assertEquals(0, first.seq)
        // Packet 0 alone, which both the Vorbis and the Opus mapping require.
        assertEquals(opusHead().size, first.bodyLen)
    }

    @Test
    fun `a tag too long for one page spans pages without losing a byte`() {
        // The lacing rule: a packet is 255s plus one value below 255, so a packet whose length
        // is an exact multiple of 255 needs a trailing zero-length segment. Leaving it out makes
        // the next packet an unterminated continuation of this one.
        val long = "y".repeat(70_000)
        val out = VorbisComment.putOgg(opusFile(), "Comment", long)!!
        assertEquals(long, VorbisComment.readOgg(out).toMap()["Comment"])
        assertTrue("the tag should have needed more than one page", VorbisComment.pages(out).size > 3)
    }

    @Test
    fun `an Opus picture survives a round trip`() {
        val art = ByteArray(2_000) { (it % 211).toByte() }
        val out = VorbisComment.putOggPicture(opusFile(), Cover.of(art))!!
        assertArrayEquals(art, VorbisComment.oggPicture(out)!!.bytes)
        // And it is not left sitting in the readable field list as base64.
        assertTrue(VorbisComment.readOgg(out).none { it.second.length > 500 })
    }

    @Test
    fun `a multiplexed stream is refused rather than half written`() {
        // Two logical streams, and no way to tell which one somebody meant to tag.
        val other = opusFile().copyOf()
        // A second stream with a different serial number, appended.
        val mixed = opusFile() + other.also {
            it[14] = 0x11
            it[15] = 0x22
        }
        assertNull(VorbisComment.putOgg(mixed, "Title", "t"))
    }

    @Test
    fun `a stream whose headers share a page with the audio is refused`() {
        // Nothing in the wild writes one, and rebuilding it would drop the start of the audio.
        // Refusing to save a tag is recoverable; losing audio is not.
        val headerAndAudio = page(1, 0x00, 0L, opusTags(VorbisComment.Tag("ref", emptyList()))) // one packet
        val packed = page(0, 0x02, 0L, opusHead()) +
            twoPacketPage(1, opusTags(VorbisComment.Tag("ref", emptyList())), audioPacket)
        assertTrue(headerAndAudio.isNotEmpty())
        assertNull(VorbisComment.putOgg(packed, "Title", "t"))
    }

    /** One page holding two complete packets, which is legal and is the case that is refused. */
    private fun twoPacketPage(seq: Int, a: ByteArray, b: ByteArray): ByteArray {
        val lacing = ArrayList<Int>()
        for (packet in listOf(a, b)) {
            var n = packet.size
            while (n >= 255) {
                lacing.add(255)
                n -= 255
            }
            lacing.add(n)
        }
        val body = a + b
        val p = ByteArray(27 + lacing.size + body.size)
        "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(p)
        fun le(at: Int, v: Int) {
            p[at] = (v and 0xFF).toByte()
            p[at + 1] = ((v ushr 8) and 0xFF).toByte()
            p[at + 2] = ((v ushr 16) and 0xFF).toByte()
            p[at + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        le(14, serial)
        le(18, seq)
        p[26] = lacing.size.toByte()
        for ((i, v) in lacing.withIndex()) p[27 + i] = v.toByte()
        body.copyInto(p, 27 + lacing.size)
        le(22, VorbisComment.pageCrc(p, 0, p.size))
        return p
    }

    @Test
    fun `a file that is not Ogg at all is refused quietly`() {
        assertNull(VorbisComment.putOgg(ByteArray(64), "Title", "t"))
        assertTrue(VorbisComment.readOgg(ByteArray(64)).isEmpty())
    }
}
