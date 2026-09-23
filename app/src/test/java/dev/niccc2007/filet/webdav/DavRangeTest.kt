package dev.niccc2007.filet.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Range arithmetic, where every mistake is silent and looks like a corrupt file. */
class DavRangeTest {

    private val total = 1000L

    @Test
    fun `no header at all means send the whole file`() {
        assertNull(DavRange.parse(null, total))
        assertNull(DavRange.parse("", total))
        assertNull(DavRange.parse("items=0-10", total))
    }

    @Test
    fun `a plain range is inclusive at both ends`() {
        val r = DavRange.parse("bytes=0-99", total)!!
        assertEquals(0, r.start)
        assertEquals(99, r.end)
        // 100, not 99: the off-by-one that truncates every file by one byte.
        assertEquals(100, r.length)
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        val r = DavRange.parse("bytes=500-", total)!!
        assertEquals(500, r.start)
        assertEquals(999, r.end)
        assertEquals(500, r.length)
    }

    @Test
    fun `a suffix range is the LAST n bytes`() {
        // Read as an absolute start this serves from byte 100 onward - the wrong 900 bytes,
        // with no error anywhere.
        val r = DavRange.parse("bytes=-100", total)!!
        assertEquals(900, r.start)
        assertEquals(999, r.end)
        assertEquals(100, r.length)
    }

    @Test
    fun `a suffix longer than the file is the whole file`() {
        val r = DavRange.parse("bytes=-5000", total)!!
        assertEquals(0, r.start)
        assertEquals(999, r.end)
        assertEquals(1000, r.length)
    }

    @Test
    fun `an end past the last byte is clamped, not refused`() {
        val r = DavRange.parse("bytes=900-99999", total)!!
        assertTrue(r.satisfiable)
        assertEquals(999, r.end)
        assertEquals(100, r.length)
    }

    @Test
    fun `the whole file as an explicit range is exactly the whole file`() {
        val r = DavRange.parse("bytes=0-999", total)!!
        assertEquals(1000, r.length)
    }

    @Test
    fun `one byte is one byte`() {
        val r = DavRange.parse("bytes=5-5", total)!!
        assertEquals(1, r.length)
    }

    // ── the unsatisfiable cases, which must be 416 and never an empty 206 ──────────────

    @Test
    fun `a start at or past the end is unsatisfiable`() {
        assertFalse(DavRange.parse("bytes=1000-", total)!!.satisfiable)
        assertFalse(DavRange.parse("bytes=5000-6000", total)!!.satisfiable)
    }

    @Test
    fun `a backwards range is unsatisfiable`() {
        assertFalse(DavRange.parse("bytes=500-100", total)!!.satisfiable)
    }

    @Test
    fun `an empty file cannot satisfy any range`() {
        assertFalse(DavRange.parse("bytes=0-10", 0)!!.satisfiable)
        assertFalse(DavRange.parse("bytes=-5", 0)!!.satisfiable)
    }

    @Test
    fun `an unsatisfiable range reports zero length`() {
        assertEquals(0, DavRange.parse("bytes=5000-6000", total)!!.length)
    }

    @Test
    fun `a zero-length suffix is unsatisfiable`() {
        assertFalse(DavRange.parse("bytes=-0", total)!!.satisfiable)
    }

    // ── malformed and unusual input ────────────────────────────────────────────────────

    @Test
    fun `garbage is treated as no range rather than guessed at`() {
        assertNull(DavRange.parse("bytes=abc-def", total))
        assertNull(DavRange.parse("bytes=", total))
        assertNull(DavRange.parse("bytes=-", total))
    }

    @Test
    fun `a file of unknown length has no range`() {
        assertNull(DavRange.parse("bytes=0-10", -1))
    }

    @Test
    fun `only the first of several ranges is honoured`() {
        val r = DavRange.parse("bytes=0-99,200-299", total)!!
        assertEquals(0, r.start)
        assertEquals(99, r.end)
    }

    @Test
    fun `the unit is matched case-insensitively and whitespace survives`() {
        assertEquals(100, DavRange.parse("  BYTES=0-99  ", total)!!.length)
    }
}
