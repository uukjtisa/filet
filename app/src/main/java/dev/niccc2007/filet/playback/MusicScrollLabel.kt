package dev.niccc2007.filet.playback

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What the fast-scroller's bubble says, for whichever ordering the list is in.
 *
 * ## Why it is per-sort and not a percentage
 *
 * A scrubber that says "43%" tells you how far down a list you are, which you already know by
 * looking at the thumb. What it cannot tell you is the thing you are scrubbing TO - and that
 * answer is different for every ordering: in a list by name it is a letter, by date it is a
 * month, by length it is a running time. A list sorted by date showing "M" is noise, and the
 * same list showing "Oct 8 2026" is navigation.
 *
 * So the bubble is derived from the sort, and the sort is the single thing that decides it.
 * Adding an ordering to [MusicSortKey] without deciding its label will not compile, which is
 * the point of the exhaustive `when` below.
 *
 * ## Why the date is spelled out
 *
 * `2/3/26` is ambiguous in two directions - the day and the month swap by country, and the year
 * is a guess - and the bubble has the room because it is only on screen while a thumb is held
 * down. Nothing is gained by compressing a label nobody reads at rest.
 *
 * Pure, and tested, because a scrubber label that disagrees with the ordering is worse than no
 * scrubber: it is a signpost pointing the wrong way.
 */
object MusicScrollLabel {

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /**
     * The bubble for [row], in the ordering [key].
     *
     * @param zone the device's zone, taken as a parameter so the date cases can be tested
     *   without the test's own clock deciding the answer.
     */
    fun of(
        key: MusicSortKey,
        name: String,
        addedAtMs: Long,
        modifiedAtMs: Long,
        sizeBytes: Long,
        durationMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = when (key) {
        MusicSortKey.NAME -> initial(name)
        MusicSortKey.ADDED -> date(addedAtMs, zone)
        MusicSortKey.MODIFIED -> date(modifiedAtMs, zone)
        MusicSortKey.SIZE -> size(sizeBytes)
        MusicSortKey.DURATION -> clock(durationMs)
    }

    /**
     * The first letter, as the list is actually sorted.
     *
     * Upper case because the sort folds case - see `CategoryQuery.audioOrder`, which collates
     * NOCASE - so `abba` and `ABBA` are in the same place and must show the same letter.
     * Anything that is not a letter groups under `#`, which is where a sort by name puts the
     * numbers and the punctuation.
     */
    fun initial(name: String): String {
        val c = name.trim().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** `Oct 8 2026`. Spelled, for the reason in the class comment. */
    fun date(ms: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (ms <= 0L) return "—"
        val c = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = ms }
        return MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + c.get(Calendar.YEAR)
    }

    /**
     * `4.6 MB`, to one decimal below a gigabyte.
     *
     * Its own formatter rather than the row's, because this one is read at a glance while a
     * thumb moves: the bubble changes several times a second, and a figure whose WIDTH changes
     * with every row makes the bubble jitter.
     */
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024.0 && u < units.lastIndex) {
            v /= 1024.0
            u++
        }
        return if (u == 0) "${bytes} B" else "%.1f %s".format(Locale.US, v, units[u])
    }

    /** `3:41`, and `1:02:15` once there is an hour of it. */
    fun clock(ms: Long): String {
        if (ms <= 0L) return "—"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
