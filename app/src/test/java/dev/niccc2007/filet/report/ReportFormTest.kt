package dev.niccc2007.filet.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertFalse(ReportForm.canSend(ReportForm.Draft(title = "Crash")))
        assertFalse(ReportForm.canSend(ReportForm.Draft(title = "   ", description = "It closes on open")))
        assertFalse(ReportForm.canSend(ReportForm.Draft(description = "It closes on open")))
        assertTrue(ReportForm.canSend(ReportForm.Draft(title = "Crash", description = "It closes on open")))
    }

    @Test
    fun `everything else is optional`() {
        // No steps, no device, no log, no pictures: still sendable.
        assertNull(ReportForm.blocker(ReportForm.Draft(title = "Crash", description = "It closes on open")))
    }

    @Test
    fun `Send is never offered for a report the server would refuse`() {
        // The server's minimums and maximums (report-api/lib/config.mjs), checked before sending.
        assertNotNull("title under 3", ReportForm.blocker(ReportForm.Draft(title = "ab", description = "It closes on open")))
        assertNotNull("title over 120", ReportForm.blocker(ReportForm.Draft(title = "x".repeat(121), description = "It closes on open")))
        assertNotNull("body under 10", ReportForm.blocker(ReportForm.Draft(title = "Crash", description = "broken")))
        assertNotNull("body over 10k", ReportForm.blocker(ReportForm.Draft(title = "Crash", description = "a".repeat(10_001))))
        assertNotNull("device over 2k", ReportForm.blocker(ReportForm.Draft(title = "Crash", description = "It closes on open", device = "d".repeat(2_001))))
        assertNull(ReportForm.blocker(ReportForm.Draft(title = "x".repeat(120), description = "It closes on open", device = "d".repeat(2_000))))
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
    fun `the device text travels once, in its own field, and the GitHub page carries it in the body`() {
        val text = "Phone: Samsung SM-A125F"
        val d = filled.copy(device = text)
        val body = json(d)
        assertTrue(body.contains("\"device\":\"Phone: Samsung SM-A125F\""))
        // Not in `body` as well: the server lays it out under its own heading, and counting it
        // against the description's limit refused long-device reports.
        assertFalse(body, body.contains("### Device"))
        assertFalse(ReportForm.requestBody(d).contains("SM-A125F"))
        // The fallback page has no server to lay it out, so there it is in the body.
        assertTrue(ReportForm.issueBody(d).contains("### Device\n\n```\nPhone: Samsung SM-A125F\n```"))
    }

    @Test
    fun `the log travels as text, which is the only thing the server accepts`() {
        val d = filled.copy(log = ReportForm.Log("run 3", "line one\nline two", 17))
        val body = json(d)
        assertTrue(body, body.contains("\"log\":\"[run 3]\\nline one\\nline two\""))
        assertFalse("never an object", body.contains("\"log\":{"))
    }

    @Test
    fun `the snapshot rides in the log, whole, ahead of a log cut to fit`() {
        val big = "x".repeat(50_000)
        val d = filled.copy(snapshot = "temp 41C", log = ReportForm.Log("run 3", big, big.length.toLong()))
        val log = ReportForm.wireLog(d)!!
        assertTrue(log.startsWith("[heat and lag snapshot]\ntemp 41C"))
        assertTrue("within the server's 30,000", log.toByteArray().size <= ReportForm.LOG_MAX_BYTES)
        assertNull(ReportForm.wireLog(filled))
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
        // The server's: three pictures, one million bytes each.
        assertEquals(ReportForm.ImageVerdict.Ok, ReportForm.imageVerdict(emptyList(), 1_000_000))
        assertTrue(ReportForm.imageVerdict(List(3) { 100L }, 100) is ReportForm.ImageVerdict.No)
        assertTrue(ReportForm.imageVerdict(emptyList(), 1_000_001) is ReportForm.ImageVerdict.No)
        assertEquals(ReportForm.ImageVerdict.Ok, ReportForm.imageVerdict(listOf(1_000_000, 1_000_000), 1_000_000))
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
        assertFalse(ReportForm.failureText("request_too_large", 413, null).retryable)
        assertTrue(ReportForm.failureText(null, 503, null).message.contains("kept"))
    }

    // ── the server's answers, one test per row of its table (report-api/README.md) ──

    private fun out(status: Int, url: String? = null, code: String? = null, msg: String? = null, after: Long? = null) =
        ReportForm.outcomeOf(status, url, code, msg, after)

    @Test
    fun `filed is issue url, and a replayed draft is the same success`() {
        val url = "https://github.com/uukjtisa/filet/issues/42"
        assertEquals(ReportForm.Outcome.Filed(url), out(201, url))
        assertEquals(ReportForm.Outcome.Filed(url), out(200, url))
        val noLink = out(201, null) as ReportForm.Outcome.Failed
        assertFalse("never re-send something that may be filed", noLink.retryable)
        assertTrue(out(201, "https://evil.example/x") is ReportForm.Outcome.Failed)
    }

    @Test
    fun `every refusal the server can send gets the right retry`() {
        val retry = mapOf(
            "rate_limited" to true, "in_progress" to true, "limiter_unavailable" to true,
            "upstream_busy" to true, "upstream_failed" to true,
            "bad_json" to false, "invalid_field" to false, "duplicate" to false,
            "request_too_large" to false, "too_many_images" to false, "image_too_large" to false,
            "unsupported_media_type" to false, "image_type" to false, "image_corrupt" to false,
            "not_configured" to false, "method_not_allowed" to true, "internal" to true,
        )
        for ((code, want) in retry) {
            val f = out(400, code = code, msg = "m") as ReportForm.Outcome.Failed
            assertEquals(code, want, f.retryable)
        }
    }

    @Test
    fun `the field the server named is shown when it refuses one`() {
        val f = out(400, code = "invalid_field", msg = "The log must be text.") as ReportForm.Outcome.Failed
        assertTrue(f.message.contains("The log must be text."))
    }

    @Test
    fun `a rate limit says when to come back`() {
        assertTrue((out(429, code = "rate_limited", after = 1800) as ReportForm.Outcome.Failed).message.contains("30 minutes"))
        assertTrue((out(429, code = "rate_limited", after = 7200) as ReportForm.Outcome.Failed).message.contains("2 hours"))
        assertTrue((out(429, code = "rate_limited") as ReportForm.Outcome.Failed).message.contains("later"))
    }

    @Test
    fun `only https leaves the phone, loopback is the one exception`() {
        assertTrue(ReportForm.endpointIsSafe("https://filet-report.vercel.app/api/report"))
        assertTrue(ReportForm.endpointIsSafe("http://127.0.0.1:3000/api/report"))
        assertTrue(ReportForm.endpointIsSafe("http://localhost:3000/api/report"))
        assertFalse(ReportForm.endpointIsSafe("http://192.168.1.4:3000/api/report"))
        assertFalse(ReportForm.endpointIsSafe("http://example.com/api/report"))
        assertFalse(ReportForm.endpointIsSafe("ftp://x"))
    }

    @Test
    fun `the app holds no server secret, only an address`() {
        // The one server-side value compiled in. Anything more is a credential in a public APK.
        val fields = ReportForm::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("TOKEN", true) || it.contains("SECRET", true) || it.contains("KEY", true) })
    }
}
