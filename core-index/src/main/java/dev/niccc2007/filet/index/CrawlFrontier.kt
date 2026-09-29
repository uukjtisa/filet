package dev.niccc2007.filet.index

/**
 * Carrying a truncated crawl across a restart, and deciding when to commit.
 *
 * ## What this replaces
 *
 * A pass gets a time budget. When it runs out the walk stops, `complete` is false, and the queue
 * of directories it had discovered and not yet listed is **thrown away** - so the next pass starts
 * at the roots again. [CrawlPlan]'s own documentation says so, and the two workarounds built
 * around it are both visible in that file: `crawlKind` stops the retry loop that this caused, and
 * `dirUnchanged` lets a later pass skip a directory whose mtime still matches so it at least gets
 * further. Neither of them is a frontier.
 *
 * Keeping the frontier makes a truncated pass genuinely resumable, and that in turn makes
 * something else safe that was not safe before.
 *
 * ## Why the two halves are in one file
 *
 * The crawl commits **per directory** today - about 1,821 transactions on this device for a
 * complete pass, each one a WAL write. Batching them is the obvious saving and was not available,
 * because a batch lost to a kill was work that had to be found again from the roots.
 *
 * With a frontier on disk, a lost batch costs exactly the directories in it: they are still on the
 * queue, so the next pass walks them again. That is why the commit policy lives here rather than
 * anywhere else - it is a consequence of the frontier, not an unrelated tuning knob.
 */
object CrawlFrontier {

    /**
     * Directories to commit before opening a new transaction.
     *
     * Small enough that a kill loses little, large enough that the fsync is amortised across a
     * few hundred rows rather than paid per directory.
     */
    const val COMMIT_EVERY = 32

    /** And never hold one open longer than this, however few directories it has seen. */
    const val COMMIT_AFTER_MS = 750L

    /**
     * How many directories to read at the same time.
     *
     * Reads only. SQLite takes one writer whatever the callers do, so parallelising the writes
     * buys nothing and costs lock contention - but a directory listing is latency, and a phone
     * spends most of a crawl waiting for storage rather than working.
     *
     * Four, for the same reason [FEED_PARALLELISM] is six: a phone's storage has a queue depth in
     * single digits, and firing fifty concurrent listings at it makes every one of them slower.
     * Lower than the feed's because this runs in the background against a foreground app, and the
     * crawl losing a little throughput is preferable to the list the person is reading stuttering.
     */
    const val READ_AHEAD = 4

    /**
     * Whether a stored frontier belongs to the pass that is about to start.
     *
     * **The generation is the whole test.** A truncated pass does not advance `META_GEN` - the
     * sweep and the stamp both happen only on a complete one - so the next pass computes the same
     * generation it did last time. A frontier stamped with that generation is therefore the
     * unfinished half of the same pass, and continuing it keeps every row under one generation,
     * which is exactly what the end-of-pass sweep requires to be correct.
     *
     * Anything else starts clean. A frontier from an older generation is from a pass that has
     * since completed, and resuming it would walk a tree that has already been swept.
     *
     * @param storedGen the generation the frontier was written under.
     * @param storedRows how many directories are on it.
     * @param nextGen the generation the pass about to start would use.
     * @param kind what [crawlKind] decided this pass is.
     */
    fun canResume(storedGen: Long, storedRows: Int, nextGen: Long, kind: CrawlKind): Boolean {
        if (storedRows <= 0) return false
        if (kind == CrawlKind.NONE) return false
        // A BUILD means the index holds nothing. There is no partial state worth trusting, and
        // starting clean is both correct and no slower.
        if (kind == CrawlKind.BUILD) return false
        return storedGen == nextGen
    }

    /**
     * Whether to close the current transaction now.
     *
     * @param dirsSinceCommit directories written since the last commit.
     * @param msSinceCommit how long the current transaction has been open.
     */
    fun shouldCommit(dirsSinceCommit: Int, msSinceCommit: Long): Boolean =
        dirsSinceCommit >= COMMIT_EVERY || msSinceCommit >= COMMIT_AFTER_MS

    /**
     * How much of a truncated pass a kill can cost, in directories.
     *
     * Stated as a function so the number is checkable rather than asserted in a comment: whatever
     * the batch size is, the loss is bounded by it, and everything lost is still on the frontier.
     */
    fun worstCaseLostDirs(): Int = COMMIT_EVERY
}
