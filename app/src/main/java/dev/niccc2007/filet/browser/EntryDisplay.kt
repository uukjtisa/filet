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
 * A row has two configurable surfaces and they are shaped differently on purpose. The LINE under
 * the name takes a list - any number of parts, in a chosen order - because it has the width for
 * several and the order is half of what anyone wants from it. The COLUMN beside the name takes
 * one part or nothing, because its value is that it lines up down the list, and two columns on
 * the right of a row is the duplication this rework exists to remove.
 *
 * The defaults say the same thing twice nowhere: a file gets its size in the column and its date
 * on the line, a folder gets its item count in the column and its total and date on the line.
 * Everything a row can answer appears exactly once.
 */
data class EntryDisplay(
    /**
     * What the second line under a name is made of, in order.
     *
     * Was a boolean, which could only say "size and date, in that order, or nothing". The
     * fault was never that the line was missing something - it was that it carried things that
     * were not wanted, and the only way to say so was to turn the whole line off. See
     * [RowDetails] for the list and [RowDetails.DEFAULT] for why the file size is no longer on
     * it by default.
     *
     * An EMPTY list is a legitimate value and means no second line at all.
     */
    val parts: List<DetailPart> = RowDetails.DEFAULT,

    /**
     * What sits in the column beside a FILE's name, or null for nothing.
     *
     * Replaces `measure` and `dateColumn`, which were two booleans that between them could put
     * two columns on the right of a row - the duplication this rework removes - while still not
     * being able to express "the folder total instead of the item count", because each column's
     * content was hard-coded in the renderer.
     *
     * See [SideColumn] for the whole argument, including why a file and a folder are set
     * separately and why each is offered only the parts that can answer for it.
     */
    val sideFile: DetailPart? = SideColumn.FILE_DEFAULT,

    /** What sits in the column beside a FOLDER's name, or null for nothing. See [sideFile]. */
    val sideFolder: DetailPart? = SideColumn.FOLDER_DEFAULT,

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

    private fun midnight(t: Long): Long = scratch().run {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    // ── why these are cached ──────────────────────────────────────────────────────────────
    //
    // Both of these are called from a LIST ROW, which means once per row per composition -
    // hundreds of times a second while somebody flings a folder.
    //
    // `Calendar.getInstance()` reads the default zone and locale and builds a fresh calendar;
    // `SimpleDateFormat(pattern, locale)` parses the pattern and loads locale data. Neither is
    // cheap, and the old code built one Calendar per `midnight` call - two per date compared -
    // and one SimpleDateFormat per row. Measured on a real phone it was most of the reason the
    // app could not hold a frame: 13,329 of 13,330 frames missed vsync, at an average of
    // 19.5ms against a budget of 8.3ms.
    //
    // Thread-local rather than shared, because neither type is thread-safe and both are
    // reached from the composition thread and from background listing work. A thread-local
    // costs a map lookup; the alternative costs an allocation and a parse.
    private val calendars = ThreadLocal.withInitial { Calendar.getInstance() }
    private val formats = ThreadLocal.withInitial { HashMap<String, SimpleDateFormat>() }

    private fun scratch(): Calendar = calendars.get()!!

    private fun formatter(pattern: String): SimpleDateFormat =
        formats.get()!!.getOrPut(pattern) { SimpleDateFormat(pattern, Locale.US) }

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
        return formatter(pattern).format(Date(at))
    }

    /**
     * The subtitle under a name.
     *
     * Delegates to [RowDetails.render], which is where the composition of this line now lives.
     * Kept as a one-argument-per-field entry point because the settings preview and the row
     * both want it, and because what it USED to do - size then date, hard-coded - is now one
     * possible configuration rather than the only one.
     */
    fun subtitle(
        isDir: Boolean,
        size: Long,
        at: Long,
        d: EntryDisplay,
        count: Int? = null,
        name: String = "",
        total: FolderTotal = FolderTotal.Unknown,
        now: Long = System.currentTimeMillis(),
    ): String = RowDetails.render(
        parts = d.parts,
        isDir = isDir,
        size = size,
        at = at,
        count = count,
        name = name,
        display = d,
        total = total,
        now = now,
    )
}
