package dev.niccc2007.filet.browser

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What a row in the file list shows, and how it writes a date.
 *
 * ## The fault
 *
 * A row read `[name_folder / 2026-09-20]`, `[folder / 2020-12-22]`, `[file.txt / 36 B /
 * 2020-12-23]` - and the date in it appeared **twice**, once in the row's own subtitle and again
 * in a column on the right. So did the size. The date column was 92dp of a phone's width spent
 * repeating the line directly above it, and the name - the one thing that identifies a file - was
 * the field paying for it.
 *
 * A folder's size column, meanwhile, was always a dash. A folder has no bytes, so the column was
 * structurally empty for every folder in the list. The number a folder actually has is how many
 * things are inside it.
 *
 * ## What is configurable, and why the default is what it is
 *
 * Each field can be turned off, because which ones matter depends on what somebody is doing -
 * a photo library wants dates, a download folder wants sizes. The defaults drop the date column
 * and keep the subtitle, because that is the pair that was duplicated and the subtitle is the one
 * with room for a full date.
 */
data class EntryDisplay(
    /** The second line under a name: the size and the date. */
    val subtitle: Boolean = true,

    /**
     * The right-hand column: bytes for a file, an item count for a folder.
     *
     * One column rather than two, because they are the same question - "how big is this" - and a
     * file and a folder simply answer it in different units.
     */
    val measure: Boolean = true,

    /**
     * A separate date column on the right.
     *
     * **Off by default**, and this is the duplication that was reported: with the subtitle on,
     * this column prints the same date again a few pixels to the right.
     */
    val dateColumn: Boolean = false,

    val style: DateStyle = DateStyle.NUMERIC,
    val order: DateOrder = DateOrder.YMD,
    val separator: String = "-",

    /** "yesterday" and "3 days ago" instead of a date, for anything inside a week. */
    val relative: Boolean = true,
)

/** How the parts of a date are written. */
enum class DateStyle(val label: String) {
    /** `2026-09-27` */
    NUMERIC("All numbers"),

    /** `27 Sep 2026` */
    SHORT_MONTH("Named month"),

    /** `27 September 2026` */
    LONG_MONTH("Full month"),
}

/** Which order the day, month and year go in. */
enum class DateOrder(val label: String) {
    DMY("Day first"),
    MDY("Month first"),
    YMD("Year first"),
}

/**
 * Turning a timestamp into the string a row shows.
 *
 * Pure, and separated from the row for the reason the live preview exists: the settings screen has
 * to show exactly what the list will show, and a preview drawn by a second code path is a preview
 * of nothing.
 */
object EntryFormat {

    /**
     * @param at the timestamp.
     * @param now the clock, passed in so the relative cases are testable.
     */
    fun date(at: Long, d: EntryDisplay, now: Long = System.currentTimeMillis()): String {
        if (at <= 0L) return ""
        if (d.relative) relativeDay(at, now)?.let { return it }
        return absolute(at, d)
    }

    /**
     * "today", "yesterday", "3 days ago" - or null when it is too old to say that way.
     *
     * Counted in local calendar days rather than in elapsed milliseconds. 23:59 and 00:01 are
     * four minutes apart and are different days, and every reader means the calendar when they
     * say yesterday. Dividing a duration by 86,400,000 gets that wrong twice a day.
     */
    fun relativeDay(at: Long, now: Long): String? {
        if (at > now) return null
        val days = daysBetween(at, now)
        return when {
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days in 2..6 -> "$days days ago"
            else -> null
        }
    }

    /** Whole local days between two instants, by midnight boundaries. */
    fun daysBetween(from: Long, to: Long): Long {
        val a = midnight(from)
        val b = midnight(to)
        return (b - a) / 86_400_000L
    }

    private fun midnight(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The full date, in the configured shape. */
    fun absolute(at: Long, d: EntryDisplay): String {
        val sep = d.separator
        val month = when (d.style) {
            DateStyle.NUMERIC -> "MM"
            DateStyle.SHORT_MONTH -> "MMM"
            DateStyle.LONG_MONTH -> "MMMM"
        }
        // A named month beside a numeric separator reads as an error - "27-Sep-2026" - so a named
        // month always takes spaces whatever the separator is set to.
        val join = if (d.style == DateStyle.NUMERIC) sep else " "
        val pattern = when (d.order) {
            DateOrder.DMY -> "dd${join}$month${join}yyyy"
            DateOrder.MDY -> "$month${join}dd${join}yyyy"
            DateOrder.YMD -> "yyyy${join}$month${join}dd"
        }
        return SimpleDateFormat(pattern, Locale.US).format(Date(at))
    }

    /**
     * The right-hand column: bytes for a file, a count for a folder.
     *
     * @param count null while it is still being read, or when it cannot be read at all. Both show
     *   nothing rather than a zero - "0 items" on a folder that is merely unread is a confident
     *   wrong answer, and that column is one somebody checks before deleting something.
     */
    fun measure(isDir: Boolean, size: Long, count: Int?): String = when {
        !isDir -> if (size >= 0) humanSize(size) else ""
        count == null -> ""
        count == 1 -> "1 item"
        else -> "$count items"
    }

    /**
     * The subtitle under a name.
     *
     * A folder shows only its date: its count is already in the column, and repeating it here is
     * the duplication this whole type exists to remove.
     */
    fun subtitle(isDir: Boolean, size: Long, at: Long, d: EntryDisplay, now: Long = System.currentTimeMillis()): String {
        val when_ = date(at, d, now)
        if (isDir) return when_
        return listOf(humanSize(size), when_).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }
}
