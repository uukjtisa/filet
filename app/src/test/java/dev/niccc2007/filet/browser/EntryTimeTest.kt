package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The time of day after a date, the day written without its leading zero beside a named month,
 * and the date column growing to hold whatever the settings can print.
 */
class EntryTimeTest {

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min)
        }.timeInMillis

    private val absolute = EntryDisplay(relative = false)

    @Test
    fun `time off prints the date alone, as before`() {
        assertEquals("2026-11-02", EntryFormat.date(at(2026, 11, 2, 14, 32), absolute))
    }

    @Test
    fun `24-hour time follows the date after a dot`() {
        val d = absolute.copy(time = TimeOfDay.H24)
        assertEquals("2026-11-02 · 14:32", EntryFormat.date(at(2026, 11, 2, 14, 32), d))
        assertEquals("2026-11-02 · 00:05", EntryFormat.date(at(2026, 11, 2, 0, 5), d))
    }

    @Test
    fun `12-hour time says AM and PM, with midnight and noon as 12`() {
        val d = absolute.copy(time = TimeOfDay.H12)
        assertEquals("2026-11-02 · 2:32 PM", EntryFormat.date(at(2026, 11, 2, 14, 32), d))
        assertEquals("2026-11-02 · 12:05 AM", EntryFormat.date(at(2026, 11, 2, 0, 5), d))
        assertEquals("2026-11-02 · 12:40 PM", EntryFormat.date(at(2026, 11, 2, 12, 40), d))
    }

    @Test
    fun `a relative day carries the time too`() {
        val now = at(2026, 11, 14, 15, 10)
        val d = EntryDisplay(relative = true, time = TimeOfDay.H24)
        assertEquals("today · 14:32", EntryFormat.date(at(2026, 11, 14, 14, 32), d, now))
        assertEquals("yesterday · 09:05", EntryFormat.date(at(2026, 11, 13, 9, 5), d, now))
        assertEquals("3 days ago · 18:47", EntryFormat.date(at(2026, 11, 11, 18, 47), d, now))
    }

    @Test
    fun `a named month drops the leading zero and numbers keep it`() {
        val day = at(2026, 11, 2, 9, 0)
        assertEquals("2026-11-02", EntryFormat.absolute(day, absolute))
        assertEquals("2 Nov 2026", EntryFormat.absolute(day, absolute.copy(style = DateStyle.SHORT_MONTH, order = DateOrder.DMY)))
        assertEquals("2026 Nov 2", EntryFormat.absolute(day, absolute.copy(style = DateStyle.SHORT_MONTH)))
        assertEquals("November 2 2026", EntryFormat.absolute(day, absolute.copy(style = DateStyle.LONG_MONTH, order = DateOrder.MDY)))
        assertEquals("02-11-2026", EntryFormat.absolute(day, absolute.copy(order = DateOrder.DMY)))
    }

    @Test
    fun `the date column keeps its width until the settings can print something longer`() {
        assertEquals(SideColumn.DATE_DP, SideColumn.widthDp(DetailPart.DATE, false, EntryDisplay()))
        assertEquals(SideColumn.DATE_DP, SideColumn.widthDp(DetailPart.DATE, false, EntryDisplay(style = DateStyle.SHORT_MONTH)))
    }

    @Test
    fun `the date column grows with the time on and with a full month`() {
        val perChar = { s: String -> s.length * 6f }
        val plain = SideColumn.widthDp(DetailPart.DATE, false, EntryDisplay(), perChar)
        val timed = SideColumn.widthDp(DetailPart.DATE, false, EntryDisplay(time = TimeOfDay.H12), perChar)
        val full = SideColumn.widthDp(DetailPart.DATE, false, EntryDisplay(style = DateStyle.LONG_MONTH), perChar)
        val both = SideColumn.widthDp(
            DetailPart.DATE, false, EntryDisplay(style = DateStyle.LONG_MONTH, time = TimeOfDay.H12), perChar,
        )
        assertTrue("time widens it: $timed", timed > plain)
        assertTrue("full month widens it: $full", full >= SideColumn.DATE_DP)
        assertTrue("both is widest: $both", both > timed && both > full)
        // The widest sample is "28 September 2026 · 10:58 PM" or longer: every character counted.
        assertTrue(both >= "2026 September 28 · 10:58 PM".length * 6)
    }

    @Test
    fun `the widest sample covers relative days with the time`() {
        val samples = SideColumn.dateSamples(EntryDisplay(relative = true, time = TimeOfDay.H24))
        assertTrue(samples.toString(), samples.any { it.startsWith("6 days ago · ") })
        assertTrue(samples.toString(), samples.any { it.contains("22:58") })
    }

    @Test
    fun `other parts keep their fixed widths`() {
        assertEquals(62, SideColumn.widthDp(DetailPart.SIZE, false, EntryDisplay(time = TimeOfDay.H24)))
        assertEquals(68, SideColumn.widthDp(DetailPart.COUNT, true, EntryDisplay(time = TimeOfDay.H24)))
        assertEquals(0, SideColumn.widthDp(null, true))
    }
}
