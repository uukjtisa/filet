package dev.niccc2007.filet.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every redaction rule, with the string a careless caller would actually log.
 *
 * Each test names the secret and asserts it is gone and that the useful part of the line - the
 * key, the host, the path - is still there. A rule that removes everything would pass a weaker
 * test and make the log worthless.
 */
class RedactTest {

    @After
    fun forget() = Redact.forgetSecrets()

    private fun gone(line: String, secret: String): String {
        val out = Redact.line(line)
        assertFalse("'$secret' survived in: $out", out.contains(secret))
        return out
    }

    @Test
    fun `keyed values lose the value and keep the key`() {
        assertTrue(gone("connect password=hunter2 to nas", "hunter2").contains("password=" + Redact.MASK))
        gone("saved {\"accessCode\": \"8841-2290\"}", "8841-2290")
        gone("share access code: 99417733", "99417733")
        gone("access_code=ABCD1234&x=1", "ABCD1234")
        gone("token=ya29.a0Af", "ya29.a0Af")
        gone("PIN: 4096", "4096")
        gone("api_key='k3y-v4lu3'", "k3y-v4lu3")
        gone("Cookie: sid=abc123def", "abc123def")
    }

    @Test
    fun `the user and password in a URL both go, the host stays`() {
        val out = gone("mount smb://anna.reyes:s3cret@192.168.1.20/share", "s3cret")
        assertFalse(out.contains("anna.reyes"))
        assertTrue(out.contains("smb://" + Redact.MASK + "@192.168.1.20/share"))
        gone("ftp://bob@files.local/", "bob@")
    }

    @Test
    fun `a WiFi QR payload loses its password and nothing else`() {
        val out = gone("scanned WIFI:T:WPA;S:HomeNet;P:correct horse battery;;", "correct horse battery")
        assertTrue(out.contains("WIFI:T:WPA;"))
    }

    @Test
    fun `bearer credentials and known token formats go`() {
        gone("Authorization: Bearer abcdef0123456789", "abcdef0123456789")
        gone("using ghp_ABCDEFGHIJKLMNOPQRSTuvwx0123 for the api", "ghp_ABCDEFGHIJKLMNOPQRSTuvwx0123")
        gone("jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4f", "eyJhbGciOiJIUzI1")
        gone("aws AKIAIOSFODNN7EXAMPLE", "AKIAIOSFODNN7EXAMPLE")
        gone("key sk-proj-abcdefghijklmnopqrstu", "sk-proj-abcdefghijklmnopqrstu")
    }

    @Test
    fun `e-mail addresses are masked`() {
        val out = gone("sent to juan.delacruz@example.com ok", "juan.delacruz")
        assertTrue(out.contains(Redact.EMAIL_MASK))
    }

    @Test
    fun `a long random-looking run goes, a long filename does not`() {
        gone("blob Zm9vYmFyQmF6UXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo=", "Zm9vYmFyQmF6UXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo")
        val name = "/storage/emulated/0/DCIM/Screenshot_20240101-123456_Chrome_Browser_Window.png"
        assertEquals("opened $name", Redact.line("opened $name"))
        val deep = "/storage/emulated/0/Download/SomeFolder/AnotherFolder/Archive2024/x.zip"
        assertEquals(deep, Redact.line(deep))
    }

    @Test
    fun `paths, hosts and ordinary lines are untouched`() {
        val plain = "staged /storage/emulated/0/Download/report.pdf from 192.168.1.20 in 340ms"
        assertEquals(plain, Redact.line(plain))
        assertEquals("exit code=1", Redact.line("exit code=1"))
    }

    @Test
    fun `a registered secret is scrubbed wherever it appears, including exception text`() {
        Redact.secret("Tr0ub4dor")
        gone("java.io.IOException: auth failed for Tr0ub4dor at host", "Tr0ub4dor")
        // Too short to register without erasing ordinary numbers.
        Redact.secret("12")
        assertEquals("page 12 of 40", Redact.line("page 12 of 40"))
    }

    @Test
    fun `an over-long line is cut and says by how much`() {
        val out = Redact.line("x".repeat(Redact.MAX_LINE + 50))
        assertTrue(out.endsWith("(50 more chars)"))
        assertTrue(out.length < Redact.MAX_LINE + 40)
    }
}
