package dev.niccc2007.filet.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Id3Test {

    /** Four bytes that look like the start of an MPEG frame, standing in for the audio. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) +
        ByteArray(64) { it.toByte() }

    private fun tagged(vararg frames: Pair<String, ByteArray>): ByteArray =
        Id3.rebuild(audio, frames.toList())

    @Test
    fun `a file with no tag still has its audio found`() {
        assertEquals(0, Id3.audioStart(audio))
        assertTrue(Id3.isMp3(audio))
        assertTrue(Id3.read(audio).isEmpty())
    }

    @Test
    fun `a title survives a round trip`() {
        val out = Id3.put(audio, "Title", "Domus - first light")!!
        assertEquals(listOf("Title" to "Domus - first light"), Id3.read(out))
    }

    @Test
    fun `the audio is copied through untouched`() {
        // The single most important property here: everything else is a tag, and a writer that
        // shifts the audio by a byte produces a file that plays as noise.
        val out = Id3.put(audio, "Title", "anything")!!
        val start = Id3.audioStart(out)
        assertArrayEquals(audio, out.copyOfRange(start, out.size))
    }

    @Test
    fun `writing over an existing tag replaces only that field`() {
        val once = Id3.put(audio, "Title", "first")!!
        val twice = Id3.put(once, "Artist", "someone")!!
        val read = Id3.read(twice).toMap()
        assertEquals("first", read["Title"])
        assertEquals("someone", read["Artist"])
        assertArrayEquals(audio, twice.copyOfRange(Id3.audioStart(twice), twice.size))
    }

    @Test
    fun `an empty value clears the field`() {
        val set = Id3.put(audio, "Title", "gone soon")!!
        val cleared = Id3.put(set, "Title", "")!!
        assertTrue(Id3.read(cleared).isEmpty())
    }

    @Test
    fun `a field the tag has no frame for is refused`() {
        // Rather than written into whichever frame happens to be nearest.
        assertNull(Id3.put(audio, "not-a-real-field", "x"))
    }

    @Test
    fun `a synchsafe size is not a plain integer`() {
        // The single most common way to corrupt an MP3 while editing one: seven bits per byte,
        // so 0x80 bytes never appear and a size above 127 is not what a plain read would give.
        val encoded = Id3.writeSynchsafe(1000)
        assertTrue("no byte may have its top bit set", encoded.all { (it.toInt() and 0x80) == 0 })
        assertEquals(1000, Id3.readSynchsafe(encoded, 0))
    }

    @Test
    fun `a picture survives a round trip`() {
        val art = ByteArray(300) { (it % 251).toByte() }
        val picture = Cover("image/jpeg", kind = 3, description = "cover", bytes = art)
        val out = Id3.putPicture(audio, picture)
        val back = Id3.picture(out)
        assertNotNull(back)
        assertEquals("image/jpeg", back!!.mime)
        assertEquals(3, back.kind)
        assertEquals("cover", back.description)
        assertArrayEquals(art, back.bytes)
    }

    @Test
    fun `a picture can be removed without touching the words`() {
        val withBoth = Id3.putPicture(
            Id3.put(audio, "Title", "keeps its name")!!,
            Cover("image/png", 3, "", ByteArray(40) { 7 }),
        )
        assertNotNull(Id3.picture(withBoth))
        val stripped = Id3.putPicture(withBoth, null)
        assertNull(Id3.picture(stripped))
        assertEquals("keeps its name", Id3.read(stripped).toMap()["Title"])
    }

    @Test
    fun `a picture larger than a plain frame size still reads back`() {
        // A cover image is always over 127 bytes, which is where reading a v2.4 synchsafe size
        // as a plain integer - or the reverse - silently truncates it.
        val art = ByteArray(70_000) { (it % 255).toByte() }
        val out = Id3.putPicture(audio, Cover("image/jpeg", 3, "", art))
        assertArrayEquals(art, Id3.picture(out)!!.bytes)
    }

    @Test
    fun `the front cover is preferred over the others`() {
        val back = Id3.apicFrame(Cover("image/jpeg", 4, "back", ByteArray(10) { 1 }))
        val front = Id3.apicFrame(Cover("image/jpeg", 3, "front", ByteArray(10) { 2 }))
        val out = tagged("APIC" to back, "APIC" to front)
        assertEquals("front", Id3.picture(out)!!.description)
    }

    @Test
    fun `padding at the end of a tag is not read as a frame`() {
        // A tag is allowed to end in zeros, and reading them as a frame id is how a parser
        // invents garbage frames at the end of every file.
        val real = Id3.put(audio, "Title", "t")!!
        val padded = real.copyOfRange(0, 10) + ByteArray(40) + real.copyOfRange(10, real.size)
        assertTrue(Id3.frames(padded).none { it.first.isBlank() })
    }

    @Test
    fun `a truncated frame does not run off the end`() {
        val out = Id3.put(audio, "Title", "something long enough to matter")!!
        for (cut in listOf(12, 15, 20, 30)) {
            // Must not throw, whatever is missing.
            Id3.frames(out.copyOfRange(0, cut.coerceAtMost(out.size)))
        }
    }
}
