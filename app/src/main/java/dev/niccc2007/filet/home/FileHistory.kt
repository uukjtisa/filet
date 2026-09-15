package dev.niccc2007.filet.home

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The expanded new-files history: which day each file belongs to, and what a day weighs.
 *
 * What is better, and what this file does that Explorer's does not:
 *
 can span four
 *   months and tells you nothing about what is under it. Today and Yesterday are named;
 *   everything older carries its actual date.
 * - **A count and a size on every header**, so a day is worth expanding or it is not, before
 *   you expand it.
 *
 * It is all pure: timestamps in, groups out. Day boundaries are the kind of arithmetic that is
 * wrong for six months and then wrong by a day for one week in spring, so they belong in a
 * function with a test on them rather than inside a composable.
 */

/** Which timestamp to sort and group by. They are genuinely different questions. */
enum class HistorySort {
    /**
     * When Filet first noticed the file in a tracked folder.
     * The filesystem cannot answer this - a file copied in today can carry any mtime at all -
     * so it is recorded once per path by [FirstSeenStore].
     */
    FIRST_SEEN,

    /**
     * The file's own modification time.
     * A download written last year sits at last year here and at today under FIRST_SEEN, which
     * is exactly why both exist.
     */
    LAST_CHANGED,
}

/** One file on the history screen. */
data class HistoryEntry(
    val path: String,
    val name: String,
    val bytes: Long,
    val firstSeen: Long,
    val lastChanged: Long,
    /** The tracked folder it came from, for the row's second line. */
    val origin: String? = null,
) {
    fun timeFor(sort: HistorySort): Long =
        if (sort == HistorySort.FIRST_SEEN) firstSeen else lastChanged
}

/**
 * One day's worth.
 *
 * @param key a stable identifier for the day, so a collapsed group stays collapsed across a
 *   refresh. Not the label - labels move: "Today" means a different day tomorrow.
 */
data class DayGroup(
    val key: String,
    val label: String,
    val entries: List<HistoryEntry>,
) {
    val count: Int get() = entries.size
    val bytes: Long get() = entries.sumOf { it.bytes }
}

object FileHistory {

    /**
     * Group [entries] by the day of their [sort] timestamp, newest first.
     *
     * @param now the clock, passed in so the boundaries are testable rather than whatever the
     *   machine thinks at the moment the test runs.
     */
    fun group(
        entries: List<HistoryEntry>,
        sort: HistorySort,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<DayGroup> {
        val byDay = LinkedHashMap<String, MutableList<HistoryEntry>>()
        for (e in entries.sortedByDescending { it.timeFor(sort) }) {
            byDay.getOrPut(dayKey(e.timeFor(sort), zone)) { ArrayList() }.add(e)
        }
        return byDay.map { (key, items) ->
            DayGroup(key, labelFor(items.first().timeFor(sort), now, zone, locale), items)
        }
    }

    /** `2026-09-14`, in the viewer's own zone. Stable across a refresh; a label is not. */
    fun dayKey(at: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = at }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * What to call the day [at] falls in.
     *
     * Measured against local MIDNIGHT, not as a count of hours. A file from 11pm last night is
     * "Yesterday" at 1am, and `now - at < 24h` calls it "Today" - which is the bug that makes a
     * history feel subtly wrong without ever being obviously broken. The same reasoning is why
     * the difference is taken between day boundaries: a DST shift makes a day 23 or 25 hours
     * long and any arithmetic on a fixed 86,400,000 drifts across it.
     */
    fun labelFor(
        at: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val days = daysBetween(at, now, zone)
        return when {
            days == 0 -> "Today"
            days == 1 -> "Yesterday"
            else -> {
                val c = Calendar.getInstance(zone).apply { timeInMillis = at }
                val nowC = Calendar.getInstance(zone).apply { timeInMillis = now }
                // The year only appears when it is not this one. Printing "2026" on every row
                // of a list that is almost entirely this year is noise.
                val pattern = if (c.get(Calendar.YEAR) == nowC.get(Calendar.YEAR)) "EEE d MMM" else "EEE d MMM yyyy"
                java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(c.time)
            }
        }
    }

    /**
     * Whole days between the two, by calendar date rather than by elapsed milliseconds.
     *
     * Negative when [at] is in the future, which happens more than it should: a file copied off
     * a device with a wrong clock, or an archive member with a bogus timestamp. Those are
     * clamped to "Today" by the caller rather than producing "in 3 days".
     */
    fun daysBetween(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val a = midnight(at, zone)
        val b = midnight(now, zone)
        if (a >= b) return 0
        // Counted by stepping the calendar, so a 23- or 25-hour DST day counts as one day.
        val c = Calendar.getInstance(zone).apply { timeInMillis = a }
        var n = 0
        while (c.timeInMillis < b && n < 400_000) {
            c.add(Calendar.DAY_OF_MONTH, 1)
            n++
        }
        return n
    }

    private fun midnight(at: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * How many entries sit above [key] in the grouped list.
     *
     * The date picker JUMPS rather than filters - the history stays under the thumb either side
     * of the day the design calls for - so the screen needs an index, and it needs one whether or not
     * the day has anything in it.
     *
     * @return the index of the first entry on that day, or the index where it WOULD be. Never
     *   -1: a picked day with nothing in it should land you between its neighbours rather than
     *   doing nothing, which reads as the picker being broken.
     */
    fun jumpIndex(groups: List<DayGroup>, key: String): Int {
        var index = 0
        for (g in groups) {
            if (g.key == key) return index
            // Groups run newest first, so the first group OLDER than the target is where an
            // empty day belongs.
            if (g.key < key) return index
            index += 1 + g.count
        }
        return index
    }

    /** The ungrouped list, for the "no special segregation" switch. */
    fun flat(entries: List<HistoryEntry>, sort: HistorySort): List<HistoryEntry> =
        entries.sortedByDescending { it.timeFor(sort) }
}
