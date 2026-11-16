package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Locked PDFs made by another program (MuPDF), one per way a password locks a PDF: RC4 40 and
 * 128 bit, AES-128, AES-256, and AES-256 with its objects packed into object streams. Each has
 * the user password "filet" and the owner password "owner", two pages, and the title
 * "Secret title" - which is an encrypted string, so finding it in plain proves the strings
 * were decrypted and not just copied.
 */
class PdfUnlockTest {
    private val locked = listOf("rc4-40.pdf", "rc4-128.pdf", "aes-128.pdf", "aes-256.pdf", "aes-256-objstm.pdf")

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/pdf/$name")) { "fixture $name missing" }.readBytes()

    private fun unlock(name: String, password: String): Pair<PdfUnlock.Result, ByteArray> {
        val out = ByteArrayOutputStream()
        val r = PdfUnlock.unlock(ByteArrayPdf(fixture(name)), password, out)
        return r to out.toByteArray()
    }

    @Test
    fun `the check says whether a password opens the lock, and writes nothing`() {
        for (name in locked) {
            val src = ByteArrayPdf(fixture(name))
            assertEquals(name, PdfUnlock.Result.Unlocked, PdfUnlock.check(src, "filet"))
            assertEquals(name, PdfUnlock.Result.WrongPassword, PdfUnlock.check(src, "nope"))
        }
        assertEquals(PdfUnlock.Result.NotLocked, PdfUnlock.check(ByteArrayPdf(fixture("plain.pdf")), "filet"))
        // Not a PDF at all: whatever it says, never that the password opened it, and no throw.
        assertTrue(PdfUnlock.check(ByteArrayPdf("not a pdf".toByteArray()), "filet") != PdfUnlock.Result.Unlocked)
    }

    @Test
    fun `every kind opens with the user password into a plain PDF`() {
        val keep = File("build/pdfunlock").apply { mkdirs() }
        for (name in locked) {
            val (r, bytes) = unlock(name, "filet")
            assertEquals(name, PdfUnlock.Result.Unlocked, r)
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse("$name still says Encrypt", text.contains("/Encrypt"))
            assertFalse("$name is still locked", PdfUnlock.isLocked(ByteArrayPdf(bytes)))
            // "Secret title", written back as a hex string.
            assertTrue("$name lost its title", text.contains("<536563726574207469746C65>"))
            assertTrue(text.startsWith("%PDF-1.7"))
            assertTrue(text.trimEnd().endsWith("%%EOF"))
            // Kept for a reader outside the tests to open, as the last word on whether it worked.
            File(keep, name).writeBytes(bytes)
        }
    }

    @Test
    fun `the owner password opens it too`() {
        for (name in locked) assertEquals(name, PdfUnlock.Result.Unlocked, unlock(name, "owner").first)
    }

    @Test
    fun `a wrong password is refused and writes nothing`() {
        for (name in locked) {
            val (r, bytes) = unlock(name, "nope")
            assertEquals(name, PdfUnlock.Result.WrongPassword, r)
            assertEquals(0, bytes.size)
        }
    }

    @Test
    fun `an unlocked PDF is said to be so`() {
        assertFalse(PdfUnlock.isLocked(ByteArrayPdf(fixture("plain.pdf"))))
        assertEquals(PdfUnlock.Result.NotLocked, unlock("plain.pdf", "x").first)
        assertTrue(PdfUnlock.isLocked(ByteArrayPdf(fixture("aes-256.pdf"))))
    }

    @Test
    fun `something that is not a PDF is not unlocked`() {
        val r = PdfUnlock.unlock(ByteArrayPdf("not a pdf at all".toByteArray()), "filet", ByteArrayOutputStream())
        assertTrue(r is PdfUnlock.Result.Unsupported)
    }

    @Test
    fun `a PDF whose index points nowhere is rebuilt and still opens`() {
        val broken = fixture("rc4-128.pdf").let { b ->
            val t = String(b, Charsets.ISO_8859_1)
            val at = t.lastIndexOf("startxref")
            (t.substring(0, at) + "startxref\n999999\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        }
        val out = ByteArrayOutputStream()
        assertEquals(PdfUnlock.Result.Unlocked, PdfUnlock.unlock(ByteArrayPdf(broken), "filet", out))
        assertTrue(String(out.toByteArray(), Charsets.ISO_8859_1).contains("<536563726574207469746C65>"))
    }
}
