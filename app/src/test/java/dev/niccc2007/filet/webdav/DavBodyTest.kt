package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who reads the body.
 *
 * The case that matters most is the first one: a small `PUT` must hand its stream to the handler
 * and never be buffered by the loop. That was the data loss.
 */
class DavBodyTest {

    private val MAX = 256 * 1024

    private fun h(vararg pairs: Pair<String, String>) = mapOf(*pairs)
    private fun len(n: Long) = h("content-length" to n.toString())

    // ---- the data-loss case ----------------------------------------------------------------

    @Test
    fun `a small PUT streams and is never buffered`() {
        // 5,992 bytes is filet.txt, the file that was emptied by a Notepad save.
        assertEquals(DavBody.Plan.Stream(5_992L), DavBody.plan("PUT", len(5_992), MAX))
    }

    @Test
    fun `a PUT of any size streams`() {
        for (n in listOf(1L, 100L, 5_992L, MAX.toLong() - 1, MAX.toLong(), MAX.toLong() + 1, 9_000_000L)) {
            val p = DavBody.plan("PUT", len(n), MAX)
            assertTrue("length $n gave $p", p is DavBody.Plan.Stream)
            assertEquals(n, (p as DavBody.Plan.Stream).length)
        }
    }

    @Test
    fun `no size boundary changes who reads a PUT`() {
        // The old behaviour switched at 256 KB, which is why the fault looked like a permissions
        // problem: big files saved, small ones did not.
        val below = DavBody.plan("PUT", len(MAX.toLong() - 1), MAX)
        val above = DavBody.plan("PUT", len(MAX.toLong() + 1), MAX)
        assertTrue(below is DavBody.Plan.Stream)
        assertTrue(above is DavBody.Plan.Stream)
    }

    @Test
    fun `a chunked PUT streams with an unknown length`() {
        // This one truncated the file to zero and answered 201 Created, at any size.
        val p = DavBody.plan("PUT", h("transfer-encoding" to "chunked"), MAX)
        assertEquals(DavBody.Plan.Stream(null), p)
    }

    @Test
    fun `a chunked PUT is recognised in any spelling`() {
        for (v in listOf("chunked", "Chunked", "CHUNKED", "gzip, chunked")) {
            assertTrue(v, DavBody.isChunked(h("transfer-encoding" to v)))
        }
        assertFalse(DavBody.isChunked(h("transfer-encoding" to "gzip")))
        assertFalse(DavBody.isChunked(emptyMap()))
    }

    @Test
    fun `an empty PUT creates an empty file rather than failing`() {
        assertEquals(DavBody.Plan.Stream(0L), DavBody.plan("PUT", len(0), MAX))
        assertEquals(DavBody.Plan.Stream(0L), DavBody.plan("PUT", emptyMap(), MAX))
    }

    // ---- parsed bodies ---------------------------------------------------------------------

    @Test
    fun `PROPFIND and LOCK are buffered for parsing`() {
        for (m in listOf("PROPFIND", "PROPPATCH", "LOCK", "UNLOCK")) {
            assertEquals(m, DavBody.Plan.Buffer(200), DavBody.plan(m, len(200), MAX))
        }
    }

    @Test
    fun `an absurd parsed body is drained rather than held in memory`() {
        val p = DavBody.plan("PROPFIND", len(MAX.toLong() + 1), MAX)
        assertEquals(DavBody.Plan.Drain(MAX.toLong() + 1), p)
    }

    @Test
    fun `a chunked parsed body is drained, not guessed at`() {
        assertEquals(
            DavBody.Plan.Drain(null),
            DavBody.plan("PROPFIND", h("transfer-encoding" to "chunked"), MAX),
        )
    }

    @Test
    fun `a bodyless request plans nothing`() {
        for (m in listOf("GET", "HEAD", "OPTIONS", "DELETE", "MKCOL", "MOVE", "COPY")) {
            assertEquals(m, DavBody.Plan.None, DavBody.plan(m, emptyMap(), MAX))
            assertEquals(m, DavBody.Plan.None, DavBody.plan(m, len(0), MAX))
        }
        assertEquals(DavBody.Plan.None, DavBody.plan("PROPFIND", emptyMap(), MAX))
    }

    @Test
    fun `a body sent to a verb that wants none is still drained`() {
        // Otherwise the next request line is read out of the middle of it.
        assertEquals(DavBody.Plan.Drain(50L), DavBody.plan("DELETE", len(50), MAX))
        assertEquals(DavBody.Plan.Drain(50L), DavBody.plan("GET", len(50), MAX))
    }

    @Test
    fun `the method is matched case-insensitively`() {
        assertTrue(DavBody.plan("put", len(10), MAX) is DavBody.Plan.Stream)
        assertEquals(DavBody.Plan.Buffer(10), DavBody.plan("propfind", len(10), MAX))
    }

    // ---- lengths ---------------------------------------------------------------------------

    @Test
    fun `a nonsense length is treated as absent`() {
        for (v in listOf("", "  ", "abc", "-1", "12x")) {
            assertNull(v, DavBody.declaredLength(h("content-length" to v)))
        }
        assertEquals(42L, DavBody.declaredLength(len(42)))
    }

    @Test
    fun `chunked wins over a length that should not be there`() {
        // A request carrying both is malformed; the transfer encoding is what actually frames it.
        val hs = h("transfer-encoding" to "chunked", "content-length" to "99")
        assertNull(DavBody.declaredLength(hs))
        assertEquals(DavBody.Plan.Stream(null), DavBody.plan("PUT", hs, MAX))
    }

    // ---- 100-continue ----------------------------------------------------------------------

    @Test
    fun `the continue expectation is recognised in any spelling`() {
        for (v in listOf("100-continue", "100-Continue", "100-CONTINUE")) {
            assertTrue(v, DavBody.expectsContinue(h("expect" to v)))
        }
        assertFalse(DavBody.expectsContinue(emptyMap()))
        assertFalse(DavBody.expectsContinue(h("expect" to "something-else")))
    }

    // ---- refusals --------------------------------------------------------------------------

    @Test
    fun `a refused write still drains its body`() {
        assertEquals(DavBody.Plan.Drain(5_992L), DavBody.drainAfterRefusal(len(5_992)))
        assertEquals(
            DavBody.Plan.Drain(null),
            DavBody.drainAfterRefusal(h("transfer-encoding" to "chunked")),
        )
    }

    @Test
    fun `a refused write drains nothing when the client was waiting to be told`() {
        // The refusal IS the answer it was holding out for, so no bytes were ever sent. This is
        // also why the refusal must be sent before any 100 Continue.
        assertEquals(
            DavBody.Plan.None,
            DavBody.drainAfterRefusal(h("expect" to "100-continue", "content-length" to "5992")),
        )
    }

    @Test
    fun `a refused request with no body drains nothing`() {
        assertEquals(DavBody.Plan.None, DavBody.drainAfterRefusal(emptyMap()))
        assertEquals(DavBody.Plan.None, DavBody.drainAfterRefusal(len(0)))
    }
}
