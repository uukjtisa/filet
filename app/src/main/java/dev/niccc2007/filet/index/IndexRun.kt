package dev.niccc2007.filet.index

/**
 * Why a user-initiated crawl ended.
 *
 * The complaint was "why did it stop?" — and the honest answer is that the old code
 * gave up after twenty-five seconds and said nothing anyone could act on. Every way a crawl
 * can end now has a name here, and the name is shown.
 *
 * Nothing in this list is a *silent* stop. If a crawl is not running, the reason it is not
 * running is on screen.
 */
enum class CrawlEnd(val label: String, val explanation: String) {
    DONE(
        "Finished",
        "The whole tree was walked. Files added or removed since are picked up by the watcher.",
    ),
    STOPPED(
        "Stopped by you",
        "It had not finished. Starting again walks the tree from the top — the index is never left in a half-state.",
    ),
    BATTERY(
        "Paused — battery low",
        "Below 15% and not charging. Plug in and start it again; a full crawl is the most expensive thing Filet does.",
    ),
    FAILED(
        "Stopped — error",
        "Something under the crawl threw. Whatever was indexed before it threw is kept.",
    ),
}

/**
 * What a pass over the tree is *for*.
 *
 * the reason it was raised is worth recording, because the bug behind it was not what it looked
 * like.
 *
 * **The index was never being rebuilt.** It has carried a generation column for a long time: a
 * pass stamps everything it sees with a new generation and then deletes whatever still carries
 * an old one, so a crawl has always confirmed what is still there, dropped what is gone and
 * added what is new. Exactly the behaviour the design calls for. What went wrong was that the only
 * number on screen was `seen`, which counts THIS RUN — so a fresh pass reading "0 files scanned
 * so far" under an index of thirty thousand read as the index having been thrown away.
 *
 * So there was no rebuild to remove. What was missing was the app telling the truth about which
 * of the two things it was doing, and that is what this distinguishes.
 */
enum class IndexPhase {
    /** Nothing indexed yet. The first pass is genuinely building something from nothing. */
    BUILD,

    /** There is an index. This pass confirms it, prunes what is gone and adds what is new. */
    UPDATE,
}

/**
 * A user-initiated crawl, as the UI needs to see it.
 *
 * Deliberately separate from [dev.niccc2007.filet.index.IndexStatus], which describes the
 * *index*. This describes one **run** — the thing with a Stop button next to it.
 */
data class IndexRun(
    val active: Boolean = false,
    /** Files seen in this run so far. Live while [active]. */
    val seen: Long = 0,
    val startedAt: Long = 0,
    /** Null until a run has ended in this process. */
    val endedBecause: CrawlEnd? = null,
    /** Extra detail for [CrawlEnd.FAILED]; empty otherwise. */
    val note: String = "",
    /** Whether this pass is building an index or updating one. */
    val phase: IndexPhase = IndexPhase.UPDATE,
    /**
     * How many files were indexed when this run started.
     *
     * The number whose absence caused the whole report. It stays on screen throughout, so a
     * run in progress can never look like an index that reset to nothing.
     */
    val known: Long = 0,
    /** Files added by the last completed run. */
    val added: Int = 0,
    /** Files the sweep removed because the last completed run did not find them. */
    val removed: Int = 0,
    /**
     * The folder being read right now, or empty.
     *
     * Bug identified: the only live thing on the Activity row was a count, and a count moving
     * in thousands is indistinguishable from one that has stopped - so a long crawl read as a
     * hung app and invited a force-stop. A path that keeps changing is the cheapest proof of
     * life there is.
     */
    val reading: String = "",
) {
    /** The folder, shortened from the left so the part that changes stays visible. */
    val readingShort: String
        get() = shortPath(reading)

    /**
     * One line, safe to show whether or not anything has ever run.
     *
     * Separate sentences for the two phases, because they are different events. "Indexing" on
     * a device that already has thirty thousand files indexed is not what is happening, and
     * saying it is what made it look as though the index had been wiped.
     */
    val line: String
        get() = when {
            active && phase == IndexPhase.BUILD && seen > 0 ->
                "Building the index — ${count(seen)} files so far"
            active && phase == IndexPhase.BUILD -> "Building the index — starting"
            // The "of" is the whole fix: the number that was already there stays visible.
            active && known > 0 -> "Updating — checked ${count(seen)} of ${count(known)}"
            active -> "Updating — starting"
            endedBecause == null -> ""
            note.isNotEmpty() -> "${endedBecause.label} — $note"
            endedBecause != CrawlEnd.DONE -> endedBecause.label
            phase == IndexPhase.BUILD -> "Finished — ${count(known + added)} files indexed"
            added == 0 && removed == 0 -> "Up to date — nothing had changed"
            else -> "Updated — " + changes()
        }

    /** What the button that starts this should say right now. */
    val action: String
        get() = when {
            active -> "Stop indexing"
            known <= 0 -> "Build index"
            else -> "Update index"
        }

    private fun changes(): String {
        val parts = buildList {
            if (added > 0) add("${count(added.toLong())} new")
            if (removed > 0) add("${count(removed.toLong())} gone")
        }
        return parts.joinToString(", ")
    }

    private fun count(n: Long): String = if (n < 1000) n.toString() else {
        // Grouped by thousands. A bare 31206 beside a bare 12430 is genuinely hard to compare
        // at a glance, and comparing them is the entire point of the line.
        val s = n.toString()
        buildString {
            for ((i, c) in s.withIndex()) {
                if (i > 0 && (s.length - i) % 3 == 0) append(',')
                append(c)
            }
        }
    }

    companion object {
        /**
         * What the Activity row says while a crawl runs.
         *
         * The count answers "how much", the folder answers "is it alive". Both, because
         * either one alone has been misread: a count on its own looked stuck, and a path on
         * its own gives no sense of progress.
         */
        fun readingLine(seen: Long, at: String?): String {
            val files = "$seen files"
            val where = at?.takeIf { it.isNotBlank() }?.let { shortPath(it) } ?: return files
            return "$files  -  $where"
        }

        /**
         * Keep the tail, drop the head.
         *
         * A crawl walks deep, so the interesting part of a path is the end of it. Trimming
         * from the right would leave forty identical rows all reading
         * "/storage/emulated/0/Android/data/com...".
         */
        fun shortPath(p: String, max: Int = 42): String {
            if (p.length <= max) return p
            val cut = p.length - max
            val slash = p.indexOf('/', cut)
            return "…" + p.substring(if (slash in cut until p.length) slash else cut)
        }
    }
}
