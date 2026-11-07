package dev.niccc2007.filet.stream

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.webdav.DavRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The stream server's parts that need no socket: its answers, its links, and its read-ahead. */
class StreamTest {

    // ── the answer's head ──

    private fun header(h: StreamHttp.Head, name: String) = h.headers.firstOrNull { it.first == name }?.second

    @Test
    fun `no range is the whole file, with its length and that ranges are taken`() {
        val h = StreamHttp.head(DavRange.parse(null, 1000), 1000, "video/mp4")
        assertEquals(200, h.status)
        assertEquals("1000", header(h, "Content-Length"))
        assertEquals("bytes", header(h, "Accept-Ranges"))
        assertEquals("video/mp4", header(h, "Content-Type"))
    }

    @Test
    fun `a seek is a 206 with the part it is and its length`() {
        val h = StreamHttp.head(DavRange.parse("bytes=500-", 1000), 1000, "video/mp4")
        assertEquals(206, h.status)
        assertEquals("bytes 500-999/1000", header(h, "Content-Range"))
        assertEquals("500", header(h, "Content-Length"))
        val tail = StreamHttp.head(DavRange.parse("bytes=-100", 1000), 1000, "video/mp4")
        assertEquals("bytes 900-999/1000", header(tail, "Content-Range"))
        val inner = StreamHttp.head(DavRange.parse("bytes=10-19", 1000), 1000, "video/mp4")
        assertEquals("10", header(inner, "Content-Length"))
    }

    @Test
    fun `a range past the end is a 416 that says how long the file is`() {
        val h = StreamHttp.head(DavRange.parse("bytes=1000-", 1000), 1000, "video/mp4")
        assertEquals(416, h.status)
        assertEquals("bytes */1000", header(h, "Content-Range"))
        assertEquals("0", header(h, "Content-Length"))
    }

    @Test
    fun `the head ends in the blank line`() {
        val t = StreamHttp.bare(404, "Not Found").text()
        assertTrue(t.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(t.endsWith("\r\n\r\n"))
    }

    // ── the link ──

    @Test
    fun `the token is read from the link, and nothing else passes for one`() {
        val t = "0123456789abcdef0123456789abcdef"
        assertEquals(t, StreamHttp.tokenOf("/s/$t/Movie%20Night.mkv"))
        assertEquals(t, StreamHttp.tokenOf("/s/$t/x.mp4?ignored=1"))
        assertNull(StreamHttp.tokenOf("/s/short/x.mp4"))
        assertNull(StreamHttp.tokenOf("/s/0123456789ABCDEF0123456789ABCDEF/x"))
        assertNull(StreamHttp.tokenOf("/files/$t/x"))
    }

    @Test
    fun `the name in the link is percent-encoded, so a space or a slash cannot break it`() {
        assertEquals("Movie%20Night%2F1.mkv", StreamHttp.segment("Movie Night/1.mkv"))
        assertEquals("caf%C3%A9.mp3", StreamHttp.segment("café.mp3"))
    }

    // ── the token table ──

    private fun e(path: String) = StreamTokens.Entry(VPath.of("smb", path), 10, "video/mp4")

    @Test
    fun `one token a file, and a new one for another file`() {
        val t = StreamTokens()
        val a = t.mint(e("/a.mp4"))
        assertEquals(a, t.mint(e("/a.mp4")))
        assertNotEquals(a, t.mint(e("/b.mp4")))
        assertEquals(VPath.of("smb", "/a.mp4"), t.resolve(a)?.path)
        assertNull(t.resolve("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `tokens are 128 random bits`() {
        val a = StreamTokens.randomToken()
        assertEquals(32, a.length)
        assertNotEquals(a, StreamTokens.randomToken())
        assertEquals(a, StreamHttp.tokenOf("/s/$a/x"))
    }

    @Test
    fun `past the cap the least recently used goes`() {
        var n = 0
        val t = StreamTokens(cap = 2) { "%032x".format(++n) }
        val a = t.mint(e("/a"))
        val b = t.mint(e("/b"))
        t.resolve(a)
        val c = t.mint(e("/c"))
        assertNull(t.resolve(b))
        assertEquals("/a", t.resolve(a)?.path?.path)
        assertEquals("/c", t.resolve(c)?.path?.path)
    }

    // ── the read-ahead ──

    private val file = ByteArray(700_000) { (it % 251).toByte() }

    private fun opener(): suspend (Long) -> InputStream = { at -> ByteArrayInputStream(file, at.toInt(), file.size - at.toInt()) }

    private fun drain(r: ReadAhead, n: Long): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        var left = n
        while (left > 0) {
            val p = r.next(left) ?: break
            out.write(p.bytes, p.from, p.length)
            left -= p.length
        }
        out.toByteArray()
    }

    @Test
    fun `it hands out exactly the bytes from where it started`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r = ReadAhead(scope, opener(), 1234, file.size.toLong())
        assertArrayEquals(file.copyOfRange(1234, 1234 + 300_000), drain(r, 300_000))
        assertEquals(1234L + 300_000, r.position)
        r.close()
        scope.cancel()
    }

    @Test
    fun `taken over a little further on, it skips to the new start`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r = ReadAhead(scope, opener(), 0, file.size.toLong())
        drain(r, 1000)
        r.skipLater(5000)
        assertArrayEquals(file.copyOfRange(6000, 7000), drain(r, 1000))
        r.close()
        scope.cancel()
    }

    @Test
    fun `at the end it has no more, and a failing drive is named as such`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val end = ReadAhead(scope, opener(), file.size.toLong() - 10, file.size.toLong())
        assertEquals(10, drain(end, 100).size)
        assertNull(runBlocking { end.next(1) })

        val broken = ReadAhead(scope, { throw IOException("share went away") }, 0, 100)
        val failed = runCatching { runBlocking { broken.next(10) } }.exceptionOrNull()
        assertTrue(failed is ReadAhead.DriveFailed)
        scope.cancel()
    }
}
