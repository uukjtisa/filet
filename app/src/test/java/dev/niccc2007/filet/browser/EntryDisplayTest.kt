package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What a row shows.
 *
 * The report was that a row printed the same date twice - once in its subtitle and again in a
 * column beside it - and that a folder's size column was permanently a dash. So the cases worth
 * pinning are the ones about what is NOT shown: the column that is off by default, the folder
 * that does not repeat its own count, and the unknown count that stays blank rather than
 * claiming zero.
 */
class EntryDisplayTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    // ── the duplication ──

    @Test
    fun `the date column is off by default`() {
        // The whole report. With the subtitle on, that column repeats it a few pixels away and
        // costs 92dp of a phone's width doing so.
        val d = EntryDisplay()
        assertTrue(d.subtitle)
        assertFalse(d.dateColumn)
    }

    @Test
    fun `a folder does not repeat its count in its subtitle`() {
        // The count is in the column. Saying it twice is the fault, arrived at from the other
        // direction.
        val sub = EntryFormat.subtitle(isDir = true, size = -1, at = at(2026, 9, 20), d = EntryDisplay())
        assertFalse(sub, sub.contains("item"))
    }

    @Test
    fun `a file subtitle carries the size and the date`() {
        val d = EntryDisplay(relative = false)
        val sub = EntryFormat.subtitle(isDir = false, size = 36, at = at(2020, 12, 23), d = d)
        assertTrue(sub, sub.contains("36 B"))
        assertTrue(sub, sub.contains("2020"))
    }

    // ── the measure column ──

    @Test
    fun `a file measures in bytes and a folder in items`() {
        assertEquals("36 B", EntryFormat.measure(isDir = false, size = 36, count = null))
        assertEquals("4 items", EntryFormat.measure(isDir = true, size = -1, count = 4))
    }

    @Test
    fun `one item is singular`() {
        assertEquals("1 item", EntryFormat.measure(isDir = true, size = -1, count = 1))
    }

    @Test
    fun `an empty folder says so`() {
        assertEquals("0 items", EntryFormat.measure(isDir = true, size = -1, count = 0))
    }

    @Test
    fun `an unknown count shows nothing rather than zero`() {
        // While it is still being read, or when the folder cannot be read at all. This column is
        // one somebody checks before deleting something, and "0 items" on a folder that is merely
        // unread is a confident wrong answer.
        assertEquals("", EntryFormat.measure(isDir = true, size = -1, count = null))
    }

    @Test
    fun `a file with no size shows nothing`() {
        assertEquals("", EntryFormat.measure(isDir = false, size = -1, count = null))
    }

    // ── dates ──

    @Test
    fun `the three styles write the month differently`() {
        val t = at(2026, 9, 27)
        assertEquals("2026-09-27", EntryFormat.absolute(t, EntryDisplay(style = DateStyle.NUMERIC)))
        assertEquals("2026 Sep 27", EntryFormat.absolute(t, EntryDisplay(style = DateStyle.SHORT_MONTH)))
        assertEquals("2026 September 27", EntryFormat.absolute(t, EntryDisplay(style = DateStyle.LONG_MONTH)))
    }

    @Test
    fun `the three orders put the parts in different places`() {
        val t = at(2026, 9, 27)
        val d = EntryDisplay(style = DateStyle.NUMERIC, separator = "/")
        assertEquals("27/09/2026", EntryFormat.absolute(t, d.copy(order = DateOrder.DMY)))
        assertEquals("09/27/2026", EntryFormat.absolute(t, d.copy(order = DateOrder.MDY)))
        assertEquals("2026/09/27", EntryFormat.absolute(t, d.copy(order = DateOrder.YMD)))
    }

    @Test
    fun `a named month never takes a numeric separator`() {
        // "27-Sep-2026" reads as a mistake. A named month always takes spaces.
        val out = EntryFormat.absolute(
            at(2026, 9, 27),
            EntryDisplay(style = DateStyle.SHORT_MONTH, order = DateOrder.DMY, separator = "-"),
        )
        assertEquals("27 Sep 2026", out)
        assertFalse(out, out.contains("-"))
    }

    // ── relative days ──

    @Test
    fun `today yesterday and a few days ago`() {
        val now = at(2026, 9, 27, h = 14)
        assertEquals("today", EntryFormat.relativeDay(at(2026, 9, 27, h = 1), now))
        assertEquals("yesterday", EntryFormat.relativeDay(at(2026, 9, 26, h = 23), now))
        assertEquals("3 days ago", EntryFormat.relativeDay(at(2026, 9, 24, h = 8), now))
    }

    @Test
    fun `a week ago falls back to a real date`() {
        val now = at(2026, 9, 27)
        assertNull(EntryFormat.relativeDay(at(2026, 9, 20), now))
        assertTrue(EntryFormat.date(at(2026, 9, 20), EntryDisplay(), now).contains("2026"))
    }

    @Test
    fun `four minutes across midnight is yesterday, not today`() {
        // Counted in calendar days, not by dividing a duration. 23:59 and 00:01 are four minutes
        // apart and are different days, and every reader means the calendar.
        val now = at(2026, 9, 27, h = 0, min = 1)
        assertEquals("yesterday", EntryFormat.relativeDay(at(2026, 9, 26, h = 23, min = 59), now))
    }

    @Test
    fun `dividing the elapsed time is the fault being prevented`() {
        // The negative control: the obvious implementation, and what it says about those same
        // four minutes.
        val now = at(2026, 9, 27, h = 0, min = 1)
        val then = at(2026, 9, 26, h = 23, min = 59)
        assertEquals(0L, (now - then) / 86_400_000L)
        assertEquals("yesterday", EntryFormat.relativeDay(then, now))
    }

    @Test
    fun `a future timestamp is never called relative`() {
        // A file dated tomorrow is a clock problem, and "in -1 days" helps nobody.
        val now = at(2026, 9, 27)
        assertNull(EntryFormat.relativeDay(at(2026, 9, 28), now))
    }

    @Test
    fun `no timestamp shows nothing`() {
        assertEquals("", EntryFormat.date(0L, EntryDisplay()))
    }

    @Test
    fun `relative can be turned off entirely`() {
        val now = at(2026, 9, 27, h = 14)
        val out = EntryFormat.date(at(2026, 9, 27, h = 1), EntryDisplay(relative = false), now)
        assertTrue(out, out.contains("2026"))
    }
}
