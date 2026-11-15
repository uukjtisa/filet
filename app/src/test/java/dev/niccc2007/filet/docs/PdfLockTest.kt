package dev.niccc2007.filet.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Locking, changing and removing a password, each read back through [PdfUnlock] - the same
 * reader that opens files other programs locked, so a lock it opens is one the standard
 * describes and not just one this code agrees with itself about.
 *
 * The fixtures are the ones [PdfUnlockTest] describes: user password "filet", owner "owner",
 * and the encrypted title "Secret title".
 */
class PdfLockTest {
    private val locked = listOf("rc4-40.pdf", "rc4-128.pdf", "aes-128.pdf", "aes-256.pdf", "aes-256-objstm.pdf")
    private val title = "<536563726574207469746C65>"

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/pdf/$name")) { "fixture $name missing" }.readBytes()

    private fun lock(bytes: ByteArray, password: String, current: String? = null): Pair<PdfLock.Result, ByteArray> {
        val out = ByteArrayOutputStream()
        return PdfLock.lock(ByteArrayPdf(bytes), password, out, current) to out.toByteArray()
    }

    private fun unlock(bytes: ByteArray, password: String): Pair<PdfUnlock.Result, String> {
        val out = ByteArrayOutputStream()
        return PdfUnlock.unlock(ByteArrayPdf(bytes), password, out) to String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    @Test
    fun `a plain PDF locks, and opens again only with its new password`() {
        val (r, bytes) = lock(fixture("plain.pdf"), "new pass")
        assertEquals(PdfLock.Result.Written, r)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("/R 6"))
        assertTrue(text.contains("/Encrypt "))
        assertTrue(PdfUnlock.isLocked(ByteArrayPdf(bytes)))
        assertEquals(PdfUnlock.Result.WrongPassword, unlock(bytes, "filet").first)
        val (open, plain) = unlock(bytes, "new pass")
        assertEquals(PdfUnlock.Result.Unlocked, open)
        assertTrue(plain.contains("Filet") || plain.contains("/Type /Page"))
        assertTrue(PdfLock.verify(ByteArrayPdf(bytes), "new pass"))
        File("build/pdflock").apply { mkdirs() }.resolve("plain-locked.pdf").writeBytes(bytes)
    }

    @Test
    fun `every kind of lock changes to a new password, title and all`() {
        val keep = File("build/pdflock").apply { mkdirs() }
        for (name in locked) {
            val (r, bytes) = lock(fixture(name), "changed", current = "filet")
            assertEquals(name, PdfLock.Result.Written, r)
            // The title is encrypted in the new file, so it must not be readable in it as it is.
            assertFalse("$name left its title in plain", String(bytes, Charsets.ISO_8859_1).contains(title))
            assertEquals(name, PdfUnlock.Result.WrongPassword, unlock(bytes, "filet").first)
            val (open, plain) = unlock(bytes, "changed")
            assertEquals(name, PdfUnlock.Result.Unlocked, open)
            assertTrue("$name lost its title", plain.contains(title))
            keep.resolve("changed-$name").writeBytes(bytes)
        }
    }

    @Test
    fun `the current password must be right to change it`() {
        for (name in locked) {
            val (r, bytes) = lock(fixture(name), "changed", current = "nope")
            assertEquals(name, PdfLock.Result.WrongPassword, r)
            assertEquals(0, bytes.size)
        }
    }

    @Test
    fun `the owner password changes it too`() {
        assertEquals(PdfLock.Result.Written, lock(fixture("aes-256-objstm.pdf"), "changed", current = "owner").first)
    }

    @Test
    fun `removing leaves a file that opens with no password`() {
        for (name in locked) {
            val out = ByteArrayOutputStream()
            assertEquals(name, PdfLock.Result.Written, PdfLock.remove(ByteArrayPdf(fixture(name)), "filet", out))
            val bytes = out.toByteArray()
            assertFalse(name, PdfUnlock.isLocked(ByteArrayPdf(bytes)))
            assertTrue(name, PdfLock.verify(ByteArrayPdf(bytes), null))
            assertTrue(name, String(bytes, Charsets.ISO_8859_1).contains(title))
        }
    }

    @Test
    fun `a removed password is refused when wrong, and a plain file has none to remove`() {
        assertEquals(PdfLock.Result.WrongPassword, PdfLock.remove(ByteArrayPdf(fixture("aes-256.pdf")), "nope", ByteArrayOutputStream()))
        assertTrue(PdfLock.remove(ByteArrayPdf(fixture("plain.pdf")), "", ByteArrayOutputStream()) is PdfLock.Result.Unsupported)
    }

    @Test
    fun `a lock, then a change, then a removal, all chain`() {
        val once = lock(fixture("aes-256-objstm.pdf"), "one", current = "filet").second
        val twice = lock(once, "two", current = "one").second
        assertEquals(PdfUnlock.Result.Unlocked, unlock(twice, "two").first)
        val out = ByteArrayOutputStream()
        assertEquals(PdfLock.Result.Written, PdfLock.remove(ByteArrayPdf(twice), "two", out))
        assertTrue(String(out.toByteArray(), Charsets.ISO_8859_1).contains(title))
    }

    @Test
    fun `a password beyond Latin-1 locks and opens`() {
        val pw = "contraseña ключ 鍵"
        val bytes = lock(fixture("plain.pdf"), pw).second
        assertEquals(PdfUnlock.Result.Unlocked, unlock(bytes, pw).first)
        assertEquals(PdfUnlock.Result.WrongPassword, unlock(bytes, "contrasena ключ 鍵").first)
    }

    @Test
    fun `two locks with one password are not the same bytes`() {
        val a = lock(fixture("plain.pdf"), "same").second
        val b = lock(fixture("plain.pdf"), "same").second
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `verify refuses a file that is not what was asked for`() {
        val bytes = lock(fixture("plain.pdf"), "pw").second
        assertFalse(PdfLock.verify(ByteArrayPdf(bytes), "other"))
        assertFalse(PdfLock.verify(ByteArrayPdf(bytes), null))
        assertFalse(PdfLock.verify(ByteArrayPdf(fixture("plain.pdf")), "pw"))
        assertFalse(PdfLock.verify(ByteArrayPdf("not a pdf".toByteArray()), null))
    }
}
