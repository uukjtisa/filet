package dev.niccc2007.filet.ui.tabs

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The day headings, tested at the boundaries that break them.
 *
 * Written as times rather than as internal state: a timestamp goes in, a heading comes out.
 * Every case here is one where an elapsed-milliseconds implementation gives a different and
 * wrong answer, which is the point of the function existing.
 */
class DayBucketsTest {

    private val manila: TimeZone = TimeZone.getTimeZone("Asia/Manila")

    private fun at(
        y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, zone: TimeZone = manila,
    ): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(y, m - 1, d, h, min, 0)
        return c.timeInMillis
    }

    // ── the boundary is local midnight, not 24 elapsed hours ───────────────────────────

    @Test
    fun `late last night is yesterday even ten minutes later in the evening`() {
        val now = at(2026, 9, 23, 23, 40)
        val then = at(2026, 9, 22, 23, 50)
        // 23h50m elapsed - an elapsed-time implementation calls this "today".
        assertEquals("d1", DayBuckets.keyOf(then, now, manila))
    }

    @Test
    fun `one minute before midnight and one minute after are different days`() {
        val now = at(2026, 9, 23, 12, 0)
        val before = at(2026, 9, 22, 23, 59)
        val after = at(2026, 9, 23, 0, 1)
        assertEquals("d1", DayBuckets.keyOf(before, now, manila))
        assertEquals("d0", DayBuckets.keyOf(after, now, manila))
    }

    @Test
    fun `the same instant this morning is today`() {
        val now = at(2026, 9, 23, 9, 15)
        assertEquals("d0", DayBuckets.keyOf(at(2026, 9, 23, 0, 0), now, manila))
        assertEquals("d0", DayBuckets.keyOf(now, now, manila))
    }

    // ── the year boundary ──────────────────────────────────────────────────────────────

    @Test
    fun `new year's eve is yesterday on new year's day`() {
        val now = at(2027, 1, 1, 10, 0)
        val then = at(2026, 12, 31, 22, 0)
        // Day-of-year arithmetic gives -364 here and sorts this above today.
        assertEquals("d1", DayBuckets.keyOf(then, now, manila))
    }

    @Test
    fun `last year gets its own heading, not the earlier-this-year lump`() {
        val now = at(2026, 9, 23)
        assertEquals("y2025", DayBuckets.keyOf(at(2025, 11, 4), now, manila))
        assertEquals("2025", DayBuckets.label("y2025", at(2025, 11, 4), manila))
    }

    @Test
    fun `january second does not lump december into earlier this year`() {
        val now = at(2027, 1, 2, 10, 0)
        // Just inside the fortnight, so it keeps a dated heading across the year boundary
        // rather than being swept into a lump - 13 days ago is still a day you remember.
        val recent = at(2026, 12, 20)
        assertEquals("d13", DayBuckets.keyOf(recent, now, manila))
        assertEquals("20 December", DayBuckets.label("d13", recent, manila))

        // Past the fortnight it belongs to last year, which is a heading. The lump is only
        // ever for the current year, so December can never land in it on 2 January.
        val older = at(2026, 12, 1)
        assertNotEquals("y0", DayBuckets.keyOf(older, now, manila))
        assertEquals("y2026", DayBuckets.keyOf(older, now, manila))
    }

    // ── daylight saving ────────────────────────────────────────────────────────────────

    @Test
    fun `a spring-forward day is still one day`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 2026-03-08 is the US spring-forward: that local day is 23 hours long.
        val now = at(2026, 3, 9, 10, 0, ny)
        val then = at(2026, 3, 8, 10, 0, ny)
        assertEquals(1, DayBuckets.daysBetween(then, now, ny))
        assertEquals("d1", DayBuckets.keyOf(then, now, ny))
    }

    @Test
    fun `an autumn fall-back day is still one day`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 2026-11-01 is 25 hours long; dividing by 86400000 truncates to 1 only by luck,
        // and to 0 for any time in the extra hour.
        val now = at(2026, 11, 2, 0, 30, ny)
        val then = at(2026, 11, 1, 23, 45, ny)
        assertEquals(1, DayBuckets.daysBetween(then, now, ny))
        assertEquals("d1", DayBuckets.keyOf(then, now, ny))
    }

    // ── the named-day window and the lump ──────────────────────────────────────────────

    @Test
    fun `days inside the window get their own dated heading`() {
        val now = at(2026, 9, 23)
        val then = at(2026, 9, 18)
        assertEquals("d05", DayBuckets.keyOf(then, now, manila))
        assertEquals("18 September", DayBuckets.label("d05", then, manila))
    }

    @Test
    fun `the day the window closes falls into the lump`() {
        val now = at(2026, 9, 23)
        val inside = at(2026, 9, 11) // 12 days back
        val outside = at(2026, 9, 9) // 14 days back
        assertEquals("d12", DayBuckets.keyOf(inside, now, manila))
        assertEquals("y0", DayBuckets.keyOf(outside, now, manila))
        assertEquals("Earlier this year", DayBuckets.label("y0", outside, manila))
    }

    @Test
    fun `keys sort chronologically newest first`() {
        val now = at(2026, 9, 23)
        val keys = listOf(
            at(2025, 4, 1), at(2026, 2, 2), at(2026, 9, 11), at(2026, 9, 22), at(2026, 9, 23),
        ).map { DayBuckets.keyOf(it, now, manila) }
        assertEquals(listOf("y2025", "y0", "d12", "d1", "d0"), keys.reversed().reversed().sorted().reversed())
    }

    // ── clocks that are wrong ──────────────────────────────────────────────────────────

    @Test
    fun `a timestamp in the future is today rather than a heading of its own`() {
        val now = at(2026, 9, 23, 10, 0)
        val ahead = at(2026, 9, 25, 10, 0)
        assertEquals("d0", DayBuckets.keyOf(ahead, now, manila))
    }

    @Test
    fun `the two named days read as words and not as dates`() {
        val now = at(2026, 9, 23)
        assertEquals("Today", DayBuckets.label("d0", now, manila))
        assertEquals("Yesterday", DayBuckets.label("d1", at(2026, 9, 22), manila))
    }
}
