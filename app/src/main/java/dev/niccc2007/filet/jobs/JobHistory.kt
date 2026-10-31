package dev.niccc2007.filet.jobs

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToLong

/** How long finished jobs are kept, and what a job left running at a restart becomes. Pure. */
object JobHistory {

    /** The choices in Settings. 0 is forever. */
    val KEEP_CHOICES = listOf(1, 7, 30, 0)
    const val DEFAULT_KEEP_DAYS = 7

    /** However long they are kept, never more than this many rows: the list is drawn, not paged. */
    const val CAP = 300

    const val INTERRUPTED = "Filet closed before it finished"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun keepLabel(days: Int): String = when (days) {
        0 -> "Forever"
        1 -> "1 day"
        else -> "$days days"
    }

    /**
     * What survives: every job still running, and finished ones that ended within [keepDays].
     * Newest first, capped at [CAP].
     */
    fun prune(jobs: List<Job>, now: Long, keepDays: Int): List<Job> {
        val cutoff = if (keepDays <= 0) Long.MIN_VALUE else now - keepDays * DAY_MS
        return jobs.filter { it.running || maxOf(it.endedAt, it.startedAt) >= cutoff }.take(CAP)
    }

    /**
     * A job read back from before a restart that was still running did not finish. It is shown
     * as failed with that reason - not dropped, because the folder it was copying into is half
     * full and somebody coming back to it needs to know why.
     */
    fun afterRestart(jobs: List<Job>): List<Job> = jobs.map {
        if (!it.running) {
            it.copy(cancellable = false, paused = false, canRunAgain = false)
        } else {
            it.copy(
                state = JobState.FAILED, error = INTERRUPTED, endedAt = it.startedAt,
                cancellable = false, paused = false, canRunAgain = false, bytesPerSec = null,
            )
        }
    }

    /** The heading a finished job sits under: Today, Yesterday, or its date. */
    fun dayOf(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(
                DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "d MMM yyyy", locale),
            )
        }
    }

    /** [jobs] in order, under day headings, by when each ended. */
    fun byDay(jobs: List<Job>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<Job>>> {
        val out = LinkedHashMap<LocalDate, MutableList<Job>>()
        for (j in jobs) {
            val d = Instant.ofEpochMilli(maxOf(j.endedAt, j.startedAt)).atZone(zone).toLocalDate()
            out.getOrPut(d) { ArrayList() } += j
        }
        return out.entries.map { (d, list) ->
            dayOf(d.atStartOfDay(zone).toInstant().toEpochMilli(), now, zone) to list
        }
    }
}

/** Speed and time left. Pure. */
object JobRate {

    /**
     * How quickly the speed follows a change. Three seconds: short enough that a copy moving from
     * a card to a share shows the new speed while it is still true, long enough that one slow
     * read does not make the time left jump.
     */
    private const val TAU_MS = 3_000.0

    /**
     * The smoothed speed after one more sample, in bytes a second.
     *
     * A count that went DOWN is the next file starting from zero - the copy counts per file - and
     * says nothing about speed, so the old value stands.
     */
    fun update(previous: Double?, lastBytes: Long, lastAt: Long, bytes: Long, at: Long): Double? {
        val dt = at - lastAt
        if (dt <= 0 || bytes < lastBytes) return previous
        val instant = (bytes - lastBytes) * 1000.0 / dt
        if (previous == null) return instant
        val alpha = 1.0 - exp(-dt / TAU_MS)
        return previous + alpha * (instant - previous)
    }

    /**
     * The bar's ring: how far everything running has got, together - the average of the jobs that
     * can say. Null with nothing running; negative when nothing running can say, which the ring
     * draws as a turning arc rather than as a number it does not have.
     */
    fun overall(jobs: List<Job>): Float? {
        val running = jobs.filter { it.running }
        if (running.isEmpty()) return null
        val known = running.mapNotNull { it.progress }
        if (known.isEmpty()) return -1f
        return known.average().toFloat().coerceIn(0f, 1f)
    }

    /**
     * Time left in ms, or null when there is nothing honest to say.
     *
     * From bytes and speed when both are known; otherwise from how far it has got and how long
     * that took, once it is far enough in for the guess to mean something.
     */
    fun etaMs(job: Job, now: Long): Long? {
        val total = job.totalBytes
        val done = job.doneBytes
        val rate = job.bytesPerSec
        if (total != null && total > 0 && done != null && rate != null && rate > 1.0) {
            return ((total - done).coerceAtLeast(0) / rate * 1000.0).roundToLong()
        }
        val f = job.progress ?: return null
        if (f < 0.03f || f >= 1f) return null
        val elapsed = now - job.startedAt
        if (elapsed < 2_000) return null
        return (elapsed * (1f - f) / f).toLong()
    }
}

/** The words the activity rows are written in. Pure. */
object JobText {

    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return (if (v >= 100) "%.0f" else "%.1f").format(Locale.US, v) + " " + units[i]
    }

    fun rate(bytesPerSec: Double): String = bytes(bytesPerSec.toLong()) + "/s"

    fun eta(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 10 -> "a few seconds left"
            s < 60 -> "about ${((s + 4) / 5) * 5} s left"
            s < 3600 -> "about ${(s + 30) / 60} min left"
            else -> "about ${s / 3600} h ${(s % 3600) / 60} min left"
        }
    }

    /** "12 of 40 files", or null when the count is not known. */
    fun items(job: Job): String? {
        val total = job.totalItems ?: return null
        val done = job.doneItems ?: 0
        return "$done of $total " + if (total == 1) "item" else "items"
    }

    /** "1.2 GB of 3.4 GB", or just what has moved when the total is not known. */
    fun amount(job: Job): String? {
        val done = job.doneBytes ?: return null
        val total = job.totalBytes
        return if (total != null && total > 0) bytes(done) + " of " + bytes(total) else bytes(done) + " so far"
    }

    /** Everything about a job as plain text, for Copy details. */
    fun details(job: Job, now: Long): String = buildString {
        appendLine(job.title + if (job.subtitle.isNotEmpty()) " - " + job.subtitle else "")
        appendLine("State: " + job.state.name.lowercase() + if (job.paused) " (paused)" else "")
        if (job.from.isNotEmpty()) appendLine("From: " + job.from)
        if (job.to.isNotEmpty()) appendLine("To: " + job.to)
        items(job)?.let { appendLine("Items: $it") }
        amount(job)?.let { appendLine("Data: $it") }
        if (job.detail.isNotEmpty()) appendLine("Detail: " + job.detail)
        job.error?.let { appendLine("Error: $it") }
        appendLine("Started: " + Instant.ofEpochMilli(job.startedAt))
        if (job.endedAt > 0) appendLine("Ended: " + Instant.ofEpochMilli(job.endedAt))
        if (job.opened.isNotEmpty()) appendLine("Result: " + job.opened)
        append("Source: " + job.source)
    }
}

/** The ledger's history as stored. Pure; tolerant of rows it cannot read, which are dropped. */
object JobCodec {

    fun encode(jobs: List<Job>): String {
        val arr = JSONArray()
        for (j in jobs) {
            val o = JSONObject()
                .put("id", j.id).put("title", j.title).put("subtitle", j.subtitle)
                .put("state", j.state.name).put("detail", j.detail)
                .put("startedAt", j.startedAt).put("endedAt", j.endedAt)
                .put("source", j.source).put("opened", j.opened).put("kind", j.kind.name)
                .put("from", j.from).put("to", j.to)
            j.progress?.let { o.put("progress", it.toDouble()) }
            j.error?.let { o.put("error", it) }
            j.doneItems?.let { o.put("doneItems", it) }
            j.totalItems?.let { o.put("totalItems", it) }
            j.doneBytes?.let { o.put("doneBytes", it) }
            j.totalBytes?.let { o.put("totalBytes", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(text: String): List<Job> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val title = o.getString("title")
                val source = o.optString("source", "Filet")
                Job(
                    id = o.getLong("id"),
                    title = title,
                    subtitle = o.optString("subtitle"),
                    state = JobState.valueOf(o.getString("state")),
                    progress = if (o.has("progress")) o.getDouble("progress").toFloat() else null,
                    detail = o.optString("detail"),
                    startedAt = o.optLong("startedAt"),
                    endedAt = o.optLong("endedAt"),
                    source = source,
                    error = if (o.has("error")) o.getString("error") else null,
                    opened = o.optString("opened"),
                    kind = runCatching { JobKind.valueOf(o.getString("kind")) }.getOrDefault(JobKind.guess(title, source)),
                    from = o.optString("from"),
                    to = o.optString("to"),
                    doneItems = if (o.has("doneItems")) o.getInt("doneItems") else null,
                    totalItems = if (o.has("totalItems")) o.getInt("totalItems") else null,
                    doneBytes = if (o.has("doneBytes")) o.getLong("doneBytes") else null,
                    totalBytes = if (o.has("totalBytes")) o.getLong("totalBytes") else null,
                )
            }.getOrNull()
        }
    }
}
