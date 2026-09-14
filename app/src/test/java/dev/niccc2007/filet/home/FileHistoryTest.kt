package dev.niccc2007.filet.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Which day a file belongs to, and what a day weighs.
 *
 * Day arithmetic is the sort of thing that is right for months and then wrong by one day for a
 * week in spring, so every boundary here is measured against local MIDNIGHT rather than against
 * a count of hours. The two failures worth catching:
 *
 * - **A file from 11pm last night reading as "Today" at 1am.** That is what `now - at < 24h`
 *   does, and it makes a history feel subtly wrong without ever looking broken.
 * - **A group label used as a group identity.** "Today" means a different day tomorrow, so a
 *   collapsed group keyed by its label reopens itself at midnight.
 */
class FileHistoryTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    private val locale = Locale.UK

    /** A local timestamp in the test's zone, so the cases say what they mean. */
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private fun entry(name: String, seen: Long, changed: Long = seen, bytes: Long = 100) =
        HistoryEntry("/t/$name", name, bytes, seen, changed)

    private val now = at(2026, 9, 14, 21, 30)

    // ── the day boundary ──

    @Test fun late_last_night_is_yesterday_not_today() {
        // The one that a 24-hour window gets wrong. 11pm on the 13th, read at 1am on the 14th,
        // is two hours ago and a different day.
        val oneAm = at(2026, 9, 14, 1, 0)
        assertEquals("Yesterday", FileHistory.labelFor(at(2026, 9, 13, 23, 0), oneAm, zone, locale))
    }

    @Test fun a_minute_after_midnight_is_today() {
        val oneAm = at(2026, 9, 14, 1, 0)
        assertEquals("Today", FileHistory.labelFor(at(2026, 9, 14, 0, 1), oneAm, zone, locale))
    }

    @Test fun a_minute_before_midnight_is_yesterday() {
        val oneAm = at(2026, 9, 14, 1, 0)
        assertEquals("Yesterday", FileHistory.labelFor(at(2026, 9, 13, 23, 59), oneAm, zone, locale))
    }

    @Test fun today_and_yesterday_are_named_and_older_days_carry_their_date() {
        assertEquals("Today", FileHistory.labelFor(at(2026, 9, 14, 3), now, zone, locale))
        assertEquals("Yesterday", FileHistory.labelFor(at(2026, 9, 13, 23), now, zone, locale))
        // Asserted by STRUCTURE, not by spelling. The JVM's own locale data abbreviates
        // September as "Sept" in en-GB and "Sep" in en-US, and it has changed between JDK
        // releases - pinning the exact string tests the CLDR bundle rather than this code.
        val older = FileHistory.labelFor(at(2026, 9, 12), now, zone, locale)
        assertTrue(older, older.startsWith("Sat "))
        assertTrue(older, older.contains("12"))
        assertTrue("this year should not print a year: $older", !older.contains("2026"))
    }

    @Test fun the_year_appears_only_when_it_is_not_this_one() {
        // Printing 2026 on every row of a list that is almost entirely 2026 is noise.
        assertTrue(FileHistory.labelFor(at(2026, 3, 2), now, zone, locale).let { !it.contains("2026") })
        assertTrue(FileHistory.labelFor(at(2024, 3, 2), now, zone, locale).contains("2024"))
    }

    @Test fun a_month_boundary_is_one_day_apart_not_a_jump() {
        val firstOfOct = at(2026, 10, 1, 9)
        assertEquals("Yesterday", FileHistory.labelFor(at(2026, 9, 30, 23), firstOfOct, zone, locale))
    }

    @Test fun a_year_boundary_is_one_day_apart_too() {
        val newYear = at(2027, 1, 1, 9)
        assertEquals("Yesterday", FileHistory.labelFor(at(2026, 12, 31, 20), newYear, zone, locale))
    }

    @Test fun a_leap_day_counts_as_one_day() {
        val march = at(2028, 3, 1, 9)
        assertEquals("Yesterday", FileHistory.labelFor(at(2028, 2, 29, 10), march, zone, locale))
    }

    @Test fun a_daylight_saving_shift_does_not_move_a_file_to_the_wrong_day() {
        // A 23-hour day. Arithmetic on a fixed 86,400,000 drifts across it; stepping the
        // calendar does not. Manila has no DST, so this case uses a zone that does.
        val london = TimeZone.getTimeZone("Europe/London")
        fun t(y: Int, m: Int, d: Int, h: Int) = Calendar.getInstance(london).apply {
            clear(); set(y, m - 1, d, h, 0, 0)
        }.timeInMillis
        // 2026-03-29 is the spring forward in the UK.
        assertEquals("Yesterday", FileHistory.labelFor(t(2026, 3, 29, 14), t(2026, 3, 30, 10), london, locale))
        assertEquals("Today", FileHistory.labelFor(t(2026, 3, 29, 14), t(2026, 3, 29, 20), london, locale))
    }

    @Test fun a_file_from_the_future_is_today_rather_than_in_three_days() {
        // Real: a file off a device with a wrong clock, or an archive member with a bogus
        // timestamp. "in 3 days" in a history is worse than being slightly wrong.
        assertEquals("Today", FileHistory.labelFor(at(2026, 9, 20), now, zone, locale))
    }

    // ── grouping ──

    @Test fun files_are_grouped_by_day_newest_first() {
        val entries = listOf(
            entry("a", at(2026, 9, 14, 9)),
            entry("b", at(2026, 9, 12, 9)),
            entry("c", at(2026, 9, 14, 18)),
            entry("d", at(2026, 9, 13, 9)),
        )
        val groups = FileHistory.group(entries, HistorySort.FIRST_SEEN, now, zone, locale)
        assertEquals(listOf("2026-09-14", "2026-09-13", "2026-09-12"), groups.map { it.key })
        assertEquals(listOf("Today", "Yesterday"), groups.take(2).map { it.label })
        assertTrue(groups[2].label, groups[2].label.startsWith("Sat "))
        assertEquals(listOf("c", "a"), groups[0].entries.map { it.name })
    }

    @Test fun each_header_carries_a_count_and_a_size() {
        // The thing Explorer's own view never tells you, and the reason a header spanning four
        // months is useless.
        val entries = listOf(
            entry("a", at(2026, 9, 14, 9), bytes = 1000),
            entry("b", at(2026, 9, 14, 10), bytes = 2500),
            entry("c", at(2026, 9, 12, 9), bytes = 7),
        )
        val groups = FileHistory.group(entries, HistorySort.FIRST_SEEN, now, zone, locale)
        assertEquals(2, groups[0].count)
        assertEquals(3500, groups[0].bytes)
        assertEquals(1, groups[1].count)
        assertEquals(7, groups[1].bytes)
    }

    @Test fun a_group_is_keyed_by_its_date_and_not_by_its_label() {
        // "Today" is a different day tomorrow. A collapsed group keyed by its label reopens
        // itself at midnight, which reads as the app forgetting what you did.
        val groups = FileHistory.group(listOf(entry("a", at(2026, 9, 14, 9))), HistorySort.FIRST_SEEN, now, zone, locale)
        assertEquals("2026-09-14", groups.single().key)
        assertNotEquals(groups.single().label, groups.single().key)
    }

    @Test fun an_empty_history_produces_no_groups_rather_than_an_empty_today() {
        assertTrue(FileHistory.group(emptyList(), HistorySort.FIRST_SEEN, now, zone, locale).isEmpty())
    }

    // ── the two orderings ──

    @Test fun the_two_orderings_put_the_same_file_in_different_groups() {
        // The assertion that proves the switch does something. A download that turned up today
        // carrying last year's timestamp is the case, and it is extremely common.
        val old = entry("reviewer.pdf", seen = at(2026, 9, 14, 10), changed = at(2025, 4, 2, 10))
        val bySeen = FileHistory.group(listOf(old), HistorySort.FIRST_SEEN, now, zone, locale)
        val byChanged = FileHistory.group(listOf(old), HistorySort.LAST_CHANGED, now, zone, locale)
        assertEquals("Today", bySeen.single().label)
        assertTrue(byChanged.single().label.contains("2025"))
    }

    @Test fun the_two_orderings_can_reverse_the_list() {
        val a = entry("a", seen = at(2026, 9, 14, 10), changed = at(2020, 1, 1))
        val b = entry("b", seen = at(2026, 9, 10, 10), changed = at(2026, 9, 14, 10))
        assertEquals(listOf("a", "b"), FileHistory.flat(listOf(a, b), HistorySort.FIRST_SEEN).map { it.name })
        assertEquals(listOf("b", "a"), FileHistory.flat(listOf(a, b), HistorySort.LAST_CHANGED).map { it.name })
    }

    @Test fun the_flat_list_holds_everything_the_groups_do() {
        // His "no special segregation" switch must not also be a filter.
        val entries = (1..20).map { entry("f$it", at(2026, 9, (it % 14) + 1, 9)) }
        val grouped = FileHistory.group(entries, HistorySort.FIRST_SEEN, now, zone, locale).flatMap { it.entries }
        val flat = FileHistory.flat(entries, HistorySort.FIRST_SEEN)
        assertEquals(flat.map { it.name }.toSet(), grouped.map { it.name }.toSet())
        assertEquals(entries.size, flat.size)
    }

    // ── the date picker jumps ──

    @Test fun picking_a_day_lands_on_that_day() {
        val entries = listOf(
            entry("a", at(2026, 9, 14, 9)),
            entry("b", at(2026, 9, 12, 9)),
            entry("c", at(2026, 9, 10, 9)),
        )
        val groups = FileHistory.group(entries, HistorySort.FIRST_SEEN, now, zone, locale)
        // header + 1 row for Today, then the 12th's header.
        assertEquals(0, FileHistory.jumpIndex(groups, "2026-09-14"))
        assertEquals(2, FileHistory.jumpIndex(groups, "2026-09-12"))
        assertEquals(4, FileHistory.jumpIndex(groups, "2026-09-10"))
    }

    @Test fun picking_a_day_with_nothing_in_it_lands_between_its_neighbours() {
        // Not -1, and not "nothing happens". A picker that appears to do nothing reads as
        // broken; landing between the days either side is an answer.
        val groups = FileHistory.group(
            listOf(entry("a", at(2026, 9, 14, 9)), entry("c", at(2026, 9, 10, 9))),
            HistorySort.FIRST_SEEN, now, zone, locale,
        )
        val i = FileHistory.jumpIndex(groups, "2026-09-12")
        assertEquals(2, i)
    }

    @Test fun picking_a_day_older_than_everything_lands_at_the_end() {
        val groups = FileHistory.group(listOf(entry("a", at(2026, 9, 14, 9))), HistorySort.FIRST_SEEN, now, zone, locale)
        assertEquals(2, FileHistory.jumpIndex(groups, "2019-01-01"))
    }

    @Test fun a_day_key_is_the_local_date_not_the_utc_one() {
        // 8am in Manila is midnight UTC. Keying on UTC would file a morning's work under the
        // previous day for everybody east of Greenwich.
        assertEquals("2026-09-14", FileHistory.dayKey(at(2026, 9, 14, 8), zone))
        assertEquals("2026-09-14", FileHistory.dayKey(at(2026, 9, 14, 0, 30), zone))
        assertEquals("2026-09-14", FileHistory.dayKey(at(2026, 9, 14, 23, 30), zone))
    }
}
