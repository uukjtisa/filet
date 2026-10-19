package dev.niccc2007.filet.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormTest {

    private val filled = ReportForm.Draft(
        kind = ReportForm.Kind.FEATURE,
        title = "  Tabs at the bottom  ",
        description = "Reach is hard.",
        steps = "1. Hold the phone\n2. Reach",
    )

    private fun json(d: ReportForm.Draft) =
        ReportForm.requestJson(d, installId = "inst-1", submissionId = "sub-1") { "B64(" + it.size + ")" }

    @Test
    fun `send waits for a title and a description`() {
        assertFalse(ReportForm.canSend(ReportForm.Draft()))
        assertFalse(ReportForm.canSend(ReportForm.Draft(title = "x")))
        assertFalse(ReportForm.canSend(ReportForm.Draft(title = "   ", description = "y")))
        assertFalse(ReportForm.canSend(ReportForm.Draft(description = "y")))
        assertTrue(ReportForm.canSend(ReportForm.Draft(title = "x", description = "y")))
    }

    @Test
    fun `everything else is optional`() {
        // No steps, no device, no log, no pictures: still sendable.
        assertNull(ReportForm.blocker(ReportForm.Draft(title = "x", description = "y")))
    }

    @Test
    fun `nothing optional is attached until it is chosen`() {
        val d = ReportForm.Draft()
        assertNull(d.device)
        assertNull(d.log)
        assertTrue(d.images.isEmpty())
        val body = json(filled)
        assertTrue(body, body.contains("\"device\":null"))
        assertTrue(body, body.contains("\"log\":null"))
        assertTrue(body, body.contains("\"images\":[]"))
    }

    @Test
    fun `the request carries the form and the two ids`() {
        val body = json(filled)
        assertTrue(body.startsWith("{\"v\":1,"))
        assertTrue(body.contains("\"installId\":\"inst-1\""))
        assertTrue(body.contains("\"submissionId\":\"sub-1\""))
        assertTrue(body.contains("\"type\":\"feature\""))
        assertTrue("title is trimmed", body.contains("\"title\":\"Tabs at the bottom\""))
        assertTrue(body.contains("Reach is hard.\\n\\n### Steps to reproduce\\n\\n1. Hold the phone\\n2. Reach"))
    }

    @Test
    fun `the request holds no credential of any kind`() {
        // The app knows one address and nothing else. A field named like a secret in the
        // request is the first sign of one being added.
        val body = json(filled.copy(device = "Phone: X", images = listOf(ReportForm.Image("a.jpg", "image/jpeg", ByteArray(3)))))
        for (word in listOf("token", "secret", "password", "authorization", "apikey", "api_key", "ghp_", "github_pat_")) {
            assertFalse(word, body.lowercase().contains(word))
        }
    }

    @Test
    fun `text is escaped so a quote cannot end the field`() {
        val body = json(filled.copy(title = "say \"hi\" \\ now", description = "tab\there"))
        assertTrue(body, body.contains("\"title\":\"say \\\"hi\\\" \\\\ now\""))
        assertTrue(body, body.contains("tab\\there"))
    }

    @Test
    fun `pictures go as base64 with their declared type`() {
        val body = json(filled.copy(images = listOf(ReportForm.Image("a.jpg", "image/jpeg", ByteArray(7)))))
        assertTrue(body, body.contains("{\"name\":\"a.jpg\",\"mime\":\"image/jpeg\",\"data\":\"B64(7)\"}"))
    }

    @Test
    fun `the device text is what gets published, in the body and in its own field`() {
        val text = "Phone: Samsung SM-A125F"
        val body = json(filled.copy(device = text))
        assertTrue(body.contains("\"device\":\"Phone: Samsung SM-A125F\""))
        assertTrue(body.contains("### Device\\n\\n```\\nPhone: Samsung SM-A125F\\n```"))
    }

    @Test
    fun `the device description names the phone and never identifies it`() {
        val text = ReportForm.deviceText(
            ReportForm.Device(
                manufacturer = "samsung", model = "SM-A125F", android = "12", sdk = 31,
                abis = listOf("arm64-v8a", "armeabi-v7a"), appVersion = "0.1.20", appCode = 20,
                flavour = "github", ramMb = 3712, lowRam = false, thermal = "moderate",
                batterySaver = true, screen = "720x1600 at 300 dpi", locale = "en-PH",
            ),
        )
        assertTrue(text.contains("Phone: samsung SM-A125F"))
        assertTrue(text.contains("Android: 12 (API 31)"))
        assertTrue(text.contains("Filet: 0.1.20 (20, github)"))
        assertTrue(text.contains("Architecture: arm64-v8a, armeabi-v7a"))
        assertTrue(text.contains("Memory: 3712 MB"))
        assertTrue(text.contains("Thermal state: moderate"))
        assertTrue(text.contains("Battery saver: on"))
        for (word in listOf("serial", "imei", "android id", "account", "ip ", "ssid", "mac")) {
            assertFalse(word, text.lowercase().contains(word))
        }
    }

    @Test
    fun `facts the phone would not give are left out rather than guessed`() {
        val text = ReportForm.deviceText(
            ReportForm.Device("Google", "Pixel", "8.0", 26, emptyList(), "0.1.20", 20, "fdroid",
                null, null, null, null, null, null),
        )
        assertEquals(4, text.lines().size)
        assertTrue(text.contains("Architecture: unknown"))
    }

    @Test
    fun `a picture is refused past the count, the per-picture cap and the total`() {
        val mb = 1024L * 1024
        assertEquals(ReportForm.ImageVerdict.Ok, ReportForm.imageVerdict(emptyList(), 1 * mb))
        assertTrue(ReportForm.imageVerdict(List(5) { 100L }, 100) is ReportForm.ImageVerdict.No)
        assertTrue(ReportForm.imageVerdict(emptyList(), 3 * mb) is ReportForm.ImageVerdict.No)
        assertTrue(ReportForm.imageVerdict(listOf(2 * mb, 2 * mb, 2 * mb - 10), 20) is ReportForm.ImageVerdict.No)
        assertEquals(ReportForm.ImageVerdict.Ok, ReportForm.imageVerdict(listOf(2 * mb, 2 * mb), 2 * mb))
    }

    @Test
    fun `a large log is cut to its tail, and says so`() {
        val line = "x".repeat(99) + "\n"
        val text = line.repeat(30)
        val (kept, note) = ReportForm.fitLog(text, max = 1000)
        assertTrue(kept.toByteArray().size <= 1000)
        assertTrue("cut on a line boundary", kept.startsWith("x"))
        assertTrue(text.endsWith(kept))
        // The cut fell exactly on a line start, so all ten whole lines are kept.
        assertEquals(10, kept.lines().count { it.isNotEmpty() })
        assertTrue(note!!.startsWith("Only the last"))
        val (whole, none) = ReportForm.fitLog("short", max = 1000)
        assertEquals("short", whole)
        assertNull(none)
    }

    @Test
    fun `a crash title is the exception's own first line, without its package`() {
        val report = "Filet crash report\n2026-10-19\n\napp: x\n\njava.lang.IllegalStateException: pane 3 has no folder\n\tat a.b.C(C.kt:1)"
        assertEquals("Crash: IllegalStateException: pane 3 has no folder", ReportForm.crashTitle(report))
        assertEquals("Crash", ReportForm.crashTitle("nothing useful here"))
    }

    @Test
    fun `a failure always says the report is kept, or what to change`() {
        val net = ReportForm.failureText(null, 0, null)
        assertTrue(net.retryable)
        assertTrue(net.message.contains("kept"))
        assertTrue(ReportForm.failureText("rate_limited", 429, null).retryable)
        assertFalse(ReportForm.failureText("too_large", 413, null).retryable)
        assertTrue(ReportForm.failureText(null, 503, null).message.contains("kept"))
    }

    @Test
    fun `the app holds no server secret, only an address`() {
        // The one server-side value compiled in. Anything more is a credential in a public APK.
        val fields = ReportForm::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("TOKEN", true) || it.contains("SECRET", true) || it.contains("KEY", true) })
    }
}
