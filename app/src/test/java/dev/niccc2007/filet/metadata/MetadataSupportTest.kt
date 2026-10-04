package dev.niccc2007.filet.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The support matrix, checked for internal honesty.
 *
 * The table is a set of claims about other people's files. A claim that is wrong in the
 * optimistic direction costs somebody their data, so what is tested here is mostly that the
 * table cannot quietly become optimistic.
 *
 * Three rows of it once were pure fiction - MP3 claiming an attached picture, EXIF claiming nine
 * fields, WebP claiming a comment slot - with no code behind any of them. Every test in this file
 * passed the whole time, because they all asked whether the table was consistent with itself and
 * none of them asked whether it was connected to anything. That is what `every claim points at
 * code that keeps it` is for, and it is the most important test here.
 */
class MetadataSupportTest {

    @Test
    fun `every claim points at code that keeps it`() {
        // The one that would have caught all three fictions. A row's tier and capabilities are
        // a promise, and the container it names is what either keeps or breaks it.
        for (f in MetadataSupport.FORMATS) {
            if (f.tier != MetadataSupport.Tier.READ_ONLY) {
                assertTrue(
                    "${f.name} is ${f.tier} but its container cannot write",
                    f.engine.writes,
                )
            } else {
                assertFalse(
                    "${f.name} is READ_ONLY but its container writes - one of the two is wrong",
                    f.engine.writes,
                )
            }
            if (f.binary) {
                assertTrue(
                    "${f.name} claims an attached picture but its container has no picture path",
                    f.engine.pictures,
                )
            }
        }
    }

    @Test
    fun `a container that can carry a picture is a format that says so`() {
        // The claim in the other direction: a capability the engine has and the table hides is a
        // feature nobody can find, which is how the cover art block stayed off the screen.
        for (f in MetadataSupport.FORMATS) {
            if (f.engine.pictures) {
                assertTrue("${f.name}'s container carries pictures and the table denies it", f.binary)
            }
        }
    }

    @Test
    fun `no two rows share a container`() {
        // A row is a format, and two rows pointing at one engine means one of them is describing
        // something that does not exist separately.
        val engines = MetadataSupport.FORMATS.map { it.engine }
        assertEquals(engines.size, engines.distinct().size)
    }

    @Test
    fun `anything not fully supported says why`() {
        // A tier without a reason is a claim nobody can check, and it is the first thing that
        // rots when a format is moved between tiers.
        for (f in MetadataSupport.FORMATS) {
            if (f.tier != MetadataSupport.Tier.FULL) {
                assertNotNull("${f.name} is ${f.tier} with no caveat", f.caveat)
                assertTrue("${f.name}'s caveat is too short to explain anything", f.caveat!!.length > 40)
            }
        }
    }

    @Test
    fun `every format declares at least one field and one extension`() {
        for (f in MetadataSupport.FORMATS) {
            assertTrue("${f.name} has no fields", f.fields.isNotEmpty())
            assertTrue("${f.name} has no extensions", f.extensions.isNotEmpty())
        }
    }

    @Test
    fun `extensions are stored the way they are looked up`() {
        // Lowercase, no dot. A single stray ".JPG" makes a format invisible to the lookup and
        // the file silently unsupported.
        for (f in MetadataSupport.FORMATS) {
            for (e in f.extensions) {
                assertEquals(e, e.lowercase())
                assertFalse(e.startsWith("."))
            }
        }
    }

    @Test
    fun `only a writable tier is ever offered as a writer`() {
        for (f in MetadataSupport.FORMATS) {
            for (e in f.extensions) {
                val w = MetadataSupport.writerFor(e)
                if (w != null) {
                    assertTrue(
                        "${w.name} is offered as a writer at tier ${w.tier}",
                        w.tier != MetadataSupport.Tier.READ_ONLY,
                    )
                }
            }
        }
    }

    @Test
    fun `a read-only format is not writable even when another entry shares its extension`() {
        // jpg appears twice - a writable comment and read-only EXIF. The lookup must return
        // the writable one without that making EXIF look writable.
        val jpg = MetadataSupport.forExtension("jpg")
        assertTrue(jpg.size >= 2)
        assertEquals(MetadataSupport.Tier.FULL, jpg.first().tier)
        assertTrue(jpg.any { it.tier == MetadataSupport.Tier.READ_ONLY })
        assertEquals("JPEG comment", MetadataSupport.writerFor("jpg")?.name)
    }

    @Test
    fun `the formats with internal offsets are writable only with a stated escape hatch`() {
        // These were all READ_ONLY, and this test asserted they stay that way - correctly, at the
        // time: each one has byte offsets that a naive edit invalidates. Each now has an engine
        // that deals with its particular version of the problem, so the test is inverted on
        // purpose, and what it now guards is that none of them was promoted to FULL. PARTIAL is
        // the honest tier for "this works, and here is the file it will refuse".
        for (ext in listOf("mp4", "m4a", "mov", "pdf", "ogg", "opus")) {
            val w = MetadataSupport.writerFor(ext)
            assertNotNull("$ext should now have a writer", w)
            assertEquals("$ext must not be promoted to FULL", MetadataSupport.Tier.PARTIAL, w!!.tier)
        }
        // FLAC is the exception and it is earned: its seek offsets are measured from the first
        // audio frame rather than from the start of the file, so the header can be any size.
        assertEquals(MetadataSupport.Tier.FULL, MetadataSupport.writerFor("flac")?.tier)
    }

    @Test
    fun `EXIF stays read-only`() {
        // The one format here where writing is still not offered, and the reason is the embedded
        // thumbnail: a wrong offset makes a photo look corrupt in the gallery that shows it.
        for (f in MetadataSupport.FORMATS.filter { it.name.contains("EXIF") }) {
            assertEquals(MetadataSupport.Tier.READ_ONLY, f.tier)
        }
        assertFalse(MetadataSupport.canWrite("webp"))
    }

    @Test
    fun `the formats that are safe stay writable`() {
        for (ext in listOf("png", "jpg", "jpeg", "gif", "zip", "apk", "flac", "docx", "xlsx")) {
            assertTrue("$ext should be writable", MetadataSupport.canWrite(ext))
        }
    }

    @Test
    fun `a format claiming a custom key can really take one`() {
        // "Any field, any name" is the single most inviting claim on the screen, and the one a
        // reader checks within about four seconds of reading it.
        for (f in MetadataSupport.FORMATS.filter { it.custom && it.engine.writes }) {
            assertTrue(
                "${f.name} claims arbitrary fields but refuses one of its own declared names",
                f.fields.all { name -> name.isNotBlank() },
            )
        }
        assertTrue(VorbisComment.isValidName("A FIELD OF MY OWN"))
        assertFalse("a name containing the separator would corrupt the tag", VorbisComment.isValidName("A=B"))
    }

    @Test
    fun `a field list is read out of the engine rather than typed twice`() {
        // The drift this closes: the table used to spell its own field names, so renaming a frame
        // in the engine left the screen offering a field the writer would refuse.
        assertEquals(Id3.FIELDS.map { it.second }, MetadataSupport.writerFor("mp3")?.fields)
        assertEquals(Mp4Tags.FIELDS.map { it.first }, MetadataSupport.writerFor("m4a")?.fields)
        assertEquals(PdfInfo.FIELDS.map { it.first }, MetadataSupport.writerFor("pdf")?.fields)
        assertEquals(Ooxml.FIELDS.map { it.first }, MetadataSupport.writerFor("docx")?.fields)
        assertEquals(VorbisComment.FIELDS.map { it.first }, MetadataSupport.writerFor("flac")?.fields)
    }

    @Test
    fun `every declared field is one its engine will actually accept`() {
        // Offered on screen and refused on save is the worst of both: the field looks typed into
        // and the value disappears. Checked by asking each engine to name the field.
        for (label in Id3.FIELDS.map { it.second }) assertNotNull(label, Id3.frameFor(label))
        for (label in Mp4Tags.FIELDS.map { it.first }) assertNotNull(label, Mp4Tags.nameFor(label))
        for (label in PdfInfo.FIELDS.map { it.first }) assertNotNull(label, PdfInfo.keyFor(label))
        for (label in Ooxml.FIELDS.map { it.first }) assertNotNull(label, Ooxml.tagFor(label))
        for (label in VorbisComment.FIELDS.map { it.first }) {
            assertNotNull(label, VorbisComment.nameFor(label))
        }
    }

    @Test
    fun `lookup is case and dot insensitive`() {
        assertEquals(MetadataSupport.forExtension("png"), MetadataSupport.forExtension("PNG"))
        assertEquals(MetadataSupport.forExtension("png"), MetadataSupport.forExtension(".png"))
    }

    @Test
    fun `an unknown extension has no writer and says something useful`() {
        assertNull(MetadataSupport.writerFor("xyzzy"))
        assertTrue(MetadataSupport.refusalFor("xyzzy").contains("does not recognise"))
    }

    @Test
    fun `a refusal names the format's problem rather than the app's`() {
        // "Not supported" teaches nothing and reads as an unfinished feature.
        val mp4 = MetadataSupport.refusalFor("mp4")
        assertTrue(mp4.contains("moov") || mp4.contains("offset"))
        assertFalse(mp4.lowercase().contains("not supported"))
    }

    @Test
    fun `the bytes get the final say over the extension`() {
        // A PNG named .mp3 must not be handed to the ID3 writer. The name is a hint; the
        // container is the authority.
        val png = PngText.put(pngStub(), "Title", "x")!!
        assertTrue(MetadataSupport.forFile("mp3", png).isEmpty())
        assertNull(MetadataSupport.writerFor("mp3", png))
        assertEquals("PNG", MetadataSupport.writerFor("png", png)?.name)
    }

    @Test
    fun `every tier is populated, so the list is a real spread and not a label`() {
        val counts = MetadataSupport.countByTier()
        for (t in MetadataSupport.Tier.entries) {
            assertTrue("$t has no formats", (counts[t] ?: 0) > 0)
        }
    }

    /** The eight-byte signature plus an IHDR and an IEND, which is a PNG as far as a parser cares. */
    private fun pngStub(): ByteArray {
        val sig = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        return sig + chunk("IHDR", ByteArray(13)) + chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32()
        crc.update(type.toByteArray(Charsets.ISO_8859_1))
        crc.update(data)
        fun be(v: Long) = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        return be(data.size.toLong()) + type.toByteArray(Charsets.ISO_8859_1) + data + be(crc.value)
    }
}
