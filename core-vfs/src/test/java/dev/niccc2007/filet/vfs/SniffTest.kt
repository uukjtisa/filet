package dev.niccc2007.filet.vfs

import dev.niccc2007.filet.vfs.provider.Sniff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising an archive that has been renamed.
 *
 * Bug identified: every routing decision was made on the extension, so a zip saved as `.bin`
 * was a binary blob with no way to look inside it, even though the reader would have opened it
 * without complaint.
 *
 * The half that needs more care is the other direction. Every interesting container is the
 * same container - an app package, an ebook, a word document and a jar are all zips - so
 * matching the signature and concluding "archive" would hand all of them to the archive viewer
 * and bury the handler that actually belongs to them.
 */
class SniffTest {

    private fun head(vararg bytes: Int): ByteArray {
        val b = ByteArray(Sniff.NEEDED)
        for (i in bytes.indices) b[i] = bytes[i].toByte()
        return b
    }

    private fun tarHead(): ByteArray {
        val b = ByteArray(Sniff.NEEDED)
        for ((i, c) in "ustar".withIndex()) b[257 + i] = c.code.toByte()
        return b
    }

    // ── what the bytes say ──

    @Test
    fun `a zip is recognised however it is named`() {
        assertEquals(Sniff.Kind.ZIP, Sniff.kindOf(head(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `an empty zip and a spanned zip are still zips`() {
        // Both are real archives a phone can produce, and both would otherwise read as unknown.
        assertEquals(Sniff.Kind.ZIP, Sniff.kindOf(head(0x50, 0x4B, 0x05, 0x06)))
        assertEquals(Sniff.Kind.ZIP, Sniff.kindOf(head(0x50, 0x4B, 0x07, 0x08)))
    }

    @Test
    fun `the other formats are recognised too`() {
        assertEquals(Sniff.Kind.GZIP, Sniff.kindOf(head(0x1F, 0x8B)))
        assertEquals(Sniff.Kind.BZIP2, Sniff.kindOf(head(0x42, 0x5A, 0x68)))
        assertEquals(Sniff.Kind.XZ, Sniff.kindOf(head(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)))
        assertEquals(Sniff.Kind.SEVEN_ZIP, Sniff.kindOf(head(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        assertEquals(Sniff.Kind.RAR, Sniff.kindOf(head(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)))
    }

    @Test
    fun `a tar is found at offset 257, not at the start`() {
        // A tar has no signature at the start at all. Reading a handful of bytes would
        // classify every tar as unknown and nobody would notice until one was renamed.
        assertEquals(Sniff.Kind.TAR, Sniff.kindOf(tarHead()))
    }

    @Test
    fun `an ordinary file is not mistaken for one`() {
        assertEquals(Sniff.Kind.UNKNOWN, Sniff.kindOf(head(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(Sniff.Kind.UNKNOWN, Sniff.kindOf(head(0xFF, 0xD8, 0xFF)))
        assertEquals("plain text", Sniff.Kind.UNKNOWN, Sniff.kindOf("hello there".toByteArray()))
    }

    @Test
    fun `a file too short to judge is unknown rather than a guess`() {
        assertEquals(Sniff.Kind.UNKNOWN, Sniff.kindOf(ByteArray(0)))
        assertEquals(Sniff.Kind.UNKNOWN, Sniff.kindOf(byteArrayOf(0x50)))
    }

    // ── the report ──

    @Test
    fun `a zip named bin opens as an archive`() {
        // The whole complaint: the bytes are unambiguous and the name claims nothing.
        val c = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = false, nameIsKnownOther = false)
        assertEquals(Sniff.Confidence.LIKELY, c)
        assertTrue(Sniff.openAsArchive(c))
    }

    @Test
    fun `a zip named zip opens as an archive, certainly`() {
        val c = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = true, nameIsKnownOther = false)
        assertEquals(Sniff.Confidence.CERTAIN, c)
        assertTrue(Sniff.openAsArchive(c))
    }

    // ── the direction that would have been worse ──

    @Test
    fun `an app package is not hijacked by the archive viewer`() {
        // An APK is a zip. Matching on the signature alone would put the archive viewer ahead
        // of the inspector for every one of them.
        val c = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = false, nameIsKnownOther = true)
        assertEquals(Sniff.Confidence.CONFLICTED, c)
        assertFalse("its own handler keeps it", Sniff.openAsArchive(c))
    }

    @Test
    fun `a document is not hijacked either`() {
        // docx, odt, epub - all zips, all with a handler that belongs to them.
        val c = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = false, nameIsKnownOther = true)
        assertFalse(Sniff.openAsArchive(c))
    }

    @Test
    fun `an unrecognised file is never opened as an archive`() {
        val c = Sniff.confidence(Sniff.Kind.UNKNOWN, nameIsArchive = false, nameIsKnownOther = false)
        assertEquals(Sniff.Confidence.NONE, c)
        assertFalse(Sniff.openAsArchive(c))
    }

    @Test
    fun `a name claiming an archive that is not one is not opened as one`() {
        // The reverse forgery: something called `.zip` with nothing archive-like inside.
        val c = Sniff.confidence(Sniff.Kind.UNKNOWN, nameIsArchive = true, nameIsKnownOther = false)
        assertEquals(Sniff.Confidence.NONE, c)
        assertFalse("the bytes win", Sniff.openAsArchive(c))
    }

    // ── the control ──

    @Test
    fun `the rule genuinely distinguishes, rather than always saying yes`() {
        val yes = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = false, nameIsKnownOther = false)
        val no = Sniff.confidence(Sniff.Kind.ZIP, nameIsArchive = false, nameIsKnownOther = true)
        assertTrue(Sniff.openAsArchive(yes))
        assertFalse(Sniff.openAsArchive(no))
    }

    @Test
    fun `enough bytes are asked for to reach a tar`() {
        assertTrue("reading fewer silently misses every tar", Sniff.NEEDED >= 262)
    }
}
