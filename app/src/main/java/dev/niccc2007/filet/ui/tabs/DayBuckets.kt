package dev.niccc2007.filet.ui.tabs

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Which day heading a timestamp belongs under.
 *
 * Pulled out as a pure function because every part of it is a decision that can be wrong in a
 * way nobody notices for months:
 *
 *  - **Elapsed milliseconds are not days.** "Less than 24 hours ago" puts 23:50 yesterday under
 *    Today at 23:40 this evening. The boundary is local midnight, so the only correct way to
 *    count is to compare calendar fields in the device's zone.
 *  - **A day is not always 86,400,000 ms.** On a DST changeover it is an hour longer or
 *    shorter, so dividing a difference by a day length drifts by one for part of the year in
 *    any zone that observes it.
 *  - **Day-of-year wraps.** Subtracting day-of-year across 31 December gives a large negative
 *    number, which sorts a file from last week above today.
 *
 * The buckets are the ones the design draws: the two named days, then each individual day for a
 * fortnight, then one lump for the rest of this year, then a heading per year.
 */
object DayBuckets {

    /** How many days back still get a day of their own before the lump. */
    const val NAMED_DAYS = 14

    /**
     * A stable key for the bucket [at] falls in, given [now].
     *
     * Keys sort chronologically as strings within a run, which is what the caller groups on;
     * they are never shown. [label] turns one into what the heading reads.
     */
    fun keyOf(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val d = daysBetween(at, now, zone)
        return when {
            d <= 0 -> "d0"
            d == 1 -> "d1"
            d < NAMED_DAYS -> "d%02d".format(d)
            sameYear(at, now, zone) -> "y0"
            else -> "y${yearOf(at, zone)}"
        }
    }

    /** What the heading for [key] reads, for a timestamp [at] that falls in it. */
    fun label(key: String, at: Long, zone: TimeZone = TimeZone.getDefault()): String = when (key) {
        "d0" -> "Today"
        "d1" -> "Yesterday"
        "y0" -> "Earlier this year"
        else -> if (key.startsWith("y")) key.drop(1) else dayLabel(at, zone)
    }

    /**
     * Whole local days from [at] to [now], counting midnights crossed rather than elapsed time.
     *
     * Negative would mean a timestamp in the future - a clock change or a file whose mtime is
     * ahead of the device - which is clamped to today by [keyOf] rather than given a heading of
     * its own.
     */
    fun daysBetween(at: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val a = midnight(at, zone)
        val b = midnight(now, zone)
        // Round rather than truncate: the two midnights are exact, but the span between them
        // is 23 or 25 hours across a DST changeover, and truncating turns that into 0 days.
        return Math.round((b - a) / 86_400_000.0).toInt()
    }

    private fun midnight(t: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun sameYear(a: Long, b: Long, zone: TimeZone) = yearOf(a, zone) == yearOf(b, zone)

    private fun yearOf(t: Long, zone: TimeZone): Int {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = t
        return c.get(Calendar.YEAR)
    }

    private fun dayLabel(t: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = t
        val month = c.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) ?: ""
        return "${c.get(Calendar.DAY_OF_MONTH)} $month"
    }
}
