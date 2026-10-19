package dev.niccc2007.filet.media

/**
 * Which paths go to the media scanner, and when. Pure: the clock is a parameter.
 *
 * ## Why announcing needed a queue
 *
 * Every call used to be one `MediaScannerConnection.scanFile`, straight away. A copy of a
 * hundred photos from a share that reports each file as it lands is then a hundred scanner
 * sessions; a network drive mapped on a PC, which writes and rewrites `desktop.ini` and
 * thumbnails as it browses, is a scanner session per write for as long as it is mapped. Each
 * one is work in the media provider, which is what the battery screen bills it to.
 *
 * So paths are collected and handed over together once writing goes quiet:
 *
 * - **[quietMs]** of no new paths flushes the batch. 1.5 s is longer than the gap between files
 *   in any copy Filet runs and short enough that a photo is in the gallery before anybody has
 *   switched to look for it.
 * - **[maxWaitMs]** flushes regardless. A transfer that never pauses must still reach the
 *   gallery while it runs, not when it ends.
 * - **[maxBatch]** caps one hand-over, so the scanner is never given ten thousand paths at once.
 *
 * ## Echoes
 *
 * A write Filet announces will be seen again by Filet itself - by the catch-up sweep, which
 * compares the disk against MediaStore before the scanner has caught up, and by the folder
 * watcher, which hears the write. Neither may announce it a second time. So a path Filet wrote
 * is remembered for [echoMs], and a path that merely turns up ([found]) inside that window is
 * dropped. A path Filet did NOT write is never suppressed - that is the catch-up sweep's whole
 * job.
 *
 * A second genuine write of the same path is different: the content changed and the scanner
 * must read it again. [written] therefore always queues, and only merges with an announce of
 * the same path that has not gone out yet.
 */
class AnnounceBatcher(
    val quietMs: Long = 1_500,
    val maxWaitMs: Long = 8_000,
    /**
     * Two minutes. The scanner normally indexes a file within seconds; this is the margin for
     * a busy device, after which a path that is still missing from MediaStore is genuinely
     * missing and may be announced again.
     */
    val echoMs: Long = 120_000,
    val maxBatch: Int = 1_000,
) {
    private val pending = LinkedHashSet<String>()
    private var firstAt = NONE
    private var lastAt = NONE

    /** Paths Filet wrote, and when. Bounded by age, and by count against a runaway. */
    private val wrote = LinkedHashMap<String, Long>()

    /** Filet wrote, moved or removed these. Always queued; a repeat while pending merges. */
    fun written(paths: Collection<String>, now: Long) {
        prune(now)
        for (p in paths) {
            // Re-inserted so the map stays in age order for prune().
            wrote.remove(p)
            wrote[p] = now
            add(p, now)
        }
        while (wrote.size > MAX_REMEMBERED) wrote.remove(wrote.keys.first())
    }

    /** These were found by something other than a write. Echoes of Filet's own are dropped. */
    fun found(paths: Collection<String>, now: Long) {
        prune(now)
        for (p in paths) if (p !in wrote) add(p, now)
    }

    /** Whether Filet itself wrote [path] within the echo window. */
    fun wroteRecently(path: String, now: Long): Boolean {
        val at = wrote[path] ?: return false
        return now - at < echoMs
    }

    /** When the next batch is due, or -1 when nothing is waiting. */
    fun dueAt(): Long =
        if (pending.isEmpty()) NONE else minOf(lastAt + quietMs, firstAt + maxWaitMs)

    /** The batch, if it is due at [now]; otherwise nothing, and the batch keeps collecting. */
    fun take(now: Long): List<String> {
        if (pending.isEmpty() || now < dueAt()) return emptyList()
        val out = ArrayList<String>(minOf(pending.size, maxBatch))
        val it = pending.iterator()
        while (it.hasNext() && out.size < maxBatch) {
            out += it.next()
            it.remove()
        }
        if (pending.isEmpty()) {
            firstAt = NONE
            lastAt = NONE
        } else {
            // What is left over is already late; it goes out on the next turn, not after
            // another quiet window.
            firstAt = now - maxWaitMs
            lastAt = now
        }
        return out
    }

    private fun add(p: String, now: Long) {
        if (pending.isEmpty()) firstAt = now
        pending += p
        lastAt = now
    }

    private fun prune(now: Long) {
        val it = wrote.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= echoMs) it.remove() else break
        }
    }

    private companion object {
        const val NONE = -1L

        /** Far past any burst within two minutes; a guard on memory, not a tuning knob. */
        const val MAX_REMEMBERED = 20_000
    }
}

/**
 * Whether a process importance means a person can see the app. Pure, so it has a test.
 *
 * FOREGROUND (100) and VISIBLE (200) do; FOREGROUND_SERVICE (125), which sits between them, does
 * not - and is the case that matters, because Filet's process lives for days behind playback
 * and sharing services with nobody looking. A `<=` comparison would answer yes to it. Literals
 * rather than the `ActivityManager` constants so this runs off the device.
 */
object Visibility {
    private const val FOREGROUND = 100
    private const val VISIBLE = 200

    fun isVisibleImportance(importance: Int): Boolean = importance == FOREGROUND || importance == VISIBLE
}
