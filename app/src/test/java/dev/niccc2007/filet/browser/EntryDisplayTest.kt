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
 * Bug identified here: a row printed the same date twice - once in its subtitle and again in a
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
    fun `nothing on the right repeats the line under the name by default`() {
        // The defect itself. A date column beside a line that already carries the date repeats it
        // a few pixels away and costs 92dp of a phone's width doing so. The column is one slot
        // now, so the rule is stated as what it actually is: whatever sits on the right is not
        // also on the line, for either kind of row.
        val d = EntryDisplay()
        assertTrue(d.parts.isNotEmpty())
        assertFalse("file side repeats the line", d.sideFile in d.parts)
        assertFalse("folder side repeats the line", d.sideFolder in d.parts)
        assertFalse(d.sideFile == DetailPart.DATE || d.sideFolder == DetailPart.DATE)
    }

    @Test
    fun `a folder does not repeat its count in its detail line`() {
        // The count is in the column. Saying it twice is the fault, arrived at from the other
        // direction.
        val sub = EntryFormat.subtitle(
            isDir = true, size = -1, at = at(2026, 9, 20), d = EntryDisplay(), count = 4,
        )
        assertFalse(sub, sub.contains("item"))
    }

    @Test
    fun `a file does not repeat its size in its detail line`() {
        // The SAME duplication as the date column, and it survived the first pass because the
        // fix only looked at dates. The default printed:
        //
        //   Report.pdf            123 KB
        //   123 KB  ·  26 Oct 2026
        //
        // Two lines to say one number. The column is the better place for it - it lines up down
        // the list and can be compared at a glance - so the line carries the date alone.
        val d = EntryDisplay(relative = false)
        val sub = EntryFormat.subtitle(isDir = false, size = 36, at = at(2020, 12, 23), d = d)
        assertFalse(sub, sub.contains("36 B"))
        assertTrue(sub, sub.contains("2020"))
    }

    @Test
    fun `a folder detail line carries what is inside it, which the row could not say before`() {
        // What the freed space is spent on. A folder's own size is meaningless, and its total
        // was only available by opening Details - so the row could not answer "how big is this
        // folder" at all, which is the question somebody browsing is usually asking.
        val d = EntryDisplay(relative = false)
        val sub = EntryFormat.subtitle(
            isDir = true,
            size = 0,
            at = at(2020, 12, 23),
            d = d,
            count = 13,
            name = "New folder",
            total = FolderTotal.Settled(4_938_100_000L),
        )
        assertTrue(sub, sub.contains("GB"))
        assertTrue(sub, sub.contains("2020"))
    }

    @Test
    fun `an unmeasured folder shows no total rather than zero`() {
        // The figure arrives asynchronously, so null is the NORMAL state for the first moment a
        // row is on screen. "0 B" under every folder would be a wrong answer, not a pending one.
        val sub = EntryFormat.subtitle(
            isDir = true, size = 0, at = at(2020, 12, 23), d = EntryDisplay(), total = FolderTotal.Unknown,
        )
        assertFalse(sub, sub.contains("0 B"))
    }

    // ── the column on the right ──
    //
    // Rendered through `RowDetails.one`, the function the row itself calls for that column. These
    // used to go through a separate `EntryFormat.measure`, which was a second spelling of the same
    // two parts and is gone for exactly that reason.

    private fun side(part: DetailPart, isDir: Boolean, size: Long, count: Int?) =
        RowDetails.one(part, isDir, size, at = 0L, count = count, name = "", display = EntryDisplay())

    @Test
    fun `a file measures in bytes and a folder in items`() {
        assertEquals("36 B", side(DetailPart.SIZE, isDir = false, size = 36, count = null))
        assertEquals("4 items", side(DetailPart.COUNT, isDir = true, size = -1, count = 4))
    }

    @Test
    fun `one item is singular`() {
        assertEquals("1 item", side(DetailPart.COUNT, isDir = true, size = -1, count = 1))
    }

    @Test
    fun `an empty folder says so`() {
        assertEquals("0 items", side(DetailPart.COUNT, isDir = true, size = -1, count = 0))
    }

    @Test
    fun `an unknown count shows nothing rather than zero`() {
        // While it is still being read, or when the folder cannot be read at all. This column is
        // one somebody checks before deleting something, and "0 items" on a folder that is merely
        // unread is a confident wrong answer.
        assertEquals("", side(DetailPart.COUNT, isDir = true, size = -1, count = null))
    }

    @Test
    fun `a file with no size shows nothing`() {
        assertEquals("", side(DetailPart.SIZE, isDir = false, size = -1, count = null))
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
