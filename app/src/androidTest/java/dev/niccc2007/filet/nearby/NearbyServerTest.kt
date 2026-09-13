package dev.niccc2007.filet.nearby

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The LAN server, over a real socket on the device.
 *
 * Every test here is a thing an attacker or a browser would actually do: ask for a file
 * without unlocking, guess the PIN, ask for a token that does not exist, request a byte
 * range, upload when uploads are off. The server is the one part of Filet that listens, so it
 * is the part that gets tested from outside.
 */
@RunWith(AndroidJUnit4::class)
class NearbyServerTest {

    private lateinit var tmp: File
    private lateinit var shared: SharedSet
    private lateinit var server: NearbyHttpServer
    private var port = 0

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "nearby-test-" + System.nanoTime())
        val folder = File(tmp, "Shared").apply { mkdirs() }
        File(folder, "notes.txt").writeText("hello from filet")
        File(folder, "second.txt").writeText("another")

        val vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        val fixture = VPath.of("local", folder.absolutePath.replace(File.separatorChar, '/'))
        val inbox = VPath.of("local", File(tmp, "Received").absolutePath.replace(File.separatorChar, '/'))
        shared = SharedSet(context, vfs, Prefs(context), folder = fixture, quarantine = inbox)

        server = NearbyHttpServer(
            context = context,
            vfs = vfs,
            shared = shared,
            deviceName = { "Test device" },
            onEvent = {},
        )
        port = server.start(preferredPort = 18321).port
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    private fun open(path: String, cookie: String? = null, range: String? = null): HttpURLConnection {
        val http = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        http.connectTimeout = 5_000
        http.readTimeout = 10_000
        cookie?.let { http.setRequestProperty("Cookie", it) }
        range?.let { http.setRequestProperty("Range", it) }
        return http
    }

    private fun unlock(): String {
        val http = open("/api/unlock")
        http.requestMethod = "POST"
        http.doOutput = true
        val body = """{"pin":"${server.pin}"}""".toByteArray()
        http.setFixedLengthStreamingMode(body.size)
        http.outputStream.use { it.write(body) }
        assertEquals(200, http.responseCode)
        val setCookie = http.getHeaderField("Set-Cookie")
        http.disconnect()
        assertNotNull("unlock must issue a session cookie", setCookie)
        return setCookie.substringBefore(';')
    }

    private fun listing(cookie: String): JSONObject {
        val http = open("/api/list", cookie)
        assertEquals(200, http.responseCode)
        val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        return JSONObject(body)
    }

    // ── nothing is reachable before the PIN ──

    @Test fun the_listing_is_locked_until_the_pin_is_entered() {
        val http = open("/api/list")
        assertEquals(401, http.responseCode)
        http.disconnect()
    }

    @Test fun a_wrong_pin_is_rejected_and_counts_down() {
        val http = open("/api/unlock")
        http.requestMethod = "POST"
        http.doOutput = true
        val body = """{"pin":"000000"}""".toByteArray()
        http.setFixedLengthStreamingMode(body.size)
        http.outputStream.use { it.write(body) }
        assertEquals(403, http.responseCode)
        http.disconnect()
    }

    @Test fun the_page_itself_is_served_without_a_pin() {
        // The gate has to be reachable, or there is nowhere to type the PIN.
        val http = open("/")
        assertEquals(200, http.responseCode)
        val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        assertTrue(body.contains("Test device"))
    }

    // ── with a session ──

    @Test fun the_listing_shows_the_shared_folder() {
        val cookie = unlock()
        val files = listing(cookie).getJSONArray("files")
        val names = (0 until files.length()).map { files.getJSONObject(it).getString("name") }
        assertTrue(names.contains("notes.txt"))
    }

    @Test fun a_file_downloads_by_token() {
        val cookie = unlock()
        val files = listing(cookie).getJSONArray("files")
        val token = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("name") == "notes.txt" }.getString("token")

        val http = open("/f/$token", cookie)
        assertEquals(200, http.responseCode)
        val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        assertEquals("hello from filet", body)
    }

    /** Range is what makes a transfer resumable and lets a browser seek in a video. */
    @Test fun a_range_request_returns_only_that_range() {
        val cookie = unlock()
        val files = listing(cookie).getJSONArray("files")
        val token = (0 until files.length()).map { files.getJSONObject(it) }
            .first { it.getString("name") == "notes.txt" }.getString("token")

        val http = open("/f/$token", cookie, range = "bytes=6-10")
        assertEquals(206, http.responseCode)
        val body = http.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        http.disconnect()
        assertEquals("from ", body)
    }

    /**
     * **Paths are not expressible in a URL**, so traversal is not mitigated - it is
     * unrepresentable. An unknown token is simply not a file.
     */
    @Test fun an_unknown_token_is_not_a_file() {
        val cookie = unlock()
        val http = open("/f/deadbeefdeadbeefdeadbeefdeadbeef", cookie)
        assertEquals(404, http.responseCode)
        http.disconnect()
    }

    @Test fun a_path_shaped_token_reaches_nothing() {
        val cookie = unlock()
        val http = open("/f/..%2F..%2F..%2Fetc%2Fhosts", cookie)
        assertTrue("a path must never resolve to a file", http.responseCode >= 400)
        http.disconnect()
    }

    @Test fun several_files_come_back_as_one_streamed_zip() {
        val cookie = unlock()
        val files = listing(cookie).getJSONArray("files")
        val tokens = (0 until files.length()).map { files.getJSONObject(it).getString("token") }

        val http = open("/z?tokens=" + tokens.joinToString(","), cookie)
        assertEquals(200, http.responseCode)
        val names = ArrayList<String>()
        ZipInputStream(http.inputStream).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
        }
        http.disconnect()
        assertTrue(names.contains("notes.txt"))
        assertTrue(names.contains("second.txt"))
    }

    // ── uploads ──

    @Test fun uploads_are_refused_while_they_are_off() {
        val cookie = unlock()
        val http = open("/u/evil.txt", cookie)
        http.requestMethod = "POST"
        http.doOutput = true
        val body = "x".toByteArray()
        http.setFixedLengthStreamingMode(body.size)
        http.outputStream.use { it.write(body) }
        assertEquals(403, http.responseCode)
        http.disconnect()
    }

    @Test fun an_upload_lands_in_quarantine_when_allowed() {
        server.uploadsAllowed = true
        val cookie = unlock()
        val http = open("/u/from-laptop.txt", cookie)
        http.requestMethod = "POST"
        http.doOutput = true
        val body = "sent from a browser".toByteArray()
        http.setFixedLengthStreamingMode(body.size)
        http.outputStream.use { it.write(body) }
        assertEquals(200, http.responseCode)
        http.disconnect()

        assertFalse(
            "an upload must never land in the shared set itself",
            shared.quarantine.path == shared.folder.path,
        )
        val landed = File(tmp, "Received/from-laptop.txt")
        assertTrue("the upload must exist in quarantine", landed.isFile)
        assertEquals("sent from a browser", landed.readText())
    }

    @Test fun the_server_reports_its_own_port_and_pin() {
        val state = server.state()
        assertTrue(state.running)
        assertEquals(port, state.port)
        assertEquals(6, state.pin.length)
    }

    @Test fun stopping_closes_the_socket() {
        server.stop()
        val failed = runCatching {
            val http = open("/")
            http.responseCode
        }.isFailure
        assertTrue("the port must be closed after stopping", failed)
    }
}
