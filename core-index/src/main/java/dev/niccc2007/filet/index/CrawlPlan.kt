package dev.niccc2007.filet.index

/**
 * Which kind of pass to run, and whether to run one at all.
 *
 * Bug identified, reported as the indexing never stopping.
 *
 * A background pass was given a 25-second budget, and a truncated pass walks from the roots
 * again next time rather than resuming. On a tree that takes minutes it therefore indexed the
 * same first slice for ever. Worse, the time of the last run is only recorded after a
 * COMPLETE pass - correctly, because that is the only thing "last indexed" can honestly mean -
 * so a pass that never completes leaves that time at zero, the index reads as permanently
 * stale, and the next launch starts another one. A loop with no exit.
 *
 * Two separate facts were being carried by one number. They are separated here:
 *
 *  - **when a pass last completed**, which is what the user is told, and
 *  - **when a pass was last attempted**, which is all that should decide whether to start
 *    another one.
 *
 * And the kind of pass matters, which is the other half of the report: an index that already
 * holds rows wants *updating*, not rebuilding. A rebuild is for an index that holds nothing.
 */
enum class CrawlKind {
    /** Nothing is held. Read everything. */
    BUILD,

    /**
     * Rows are held. Visit what may have changed and leave the rest alone.
     *
     * A directory whose modification time matches what was recorded cannot have gained or
     * lost an entry, so its children do not need listing. See [dirUnchanged].
     */
    UPDATE,

    /** Nothing to do. */
    NONE,
}

/**
 * @param filesHeld how many rows the index holds right now.
 * @param lastCompleteAt when a pass last finished, or 0 if one never has.
 * @param lastAttemptAt when a pass was last started, or 0.
 * @param now the clock.
 * @param staleAfterMs how old a complete pass has to be before another is wanted.
 * @param retryAfterMs how long to wait after an attempt that did not complete, so a tree too
 *   big for one budget is not retried on every single launch.
 */
fun crawlKind(
    filesHeld: Long,
    lastCompleteAt: Long,
    lastAttemptAt: Long,
    now: Long,
    staleAfterMs: Long,
    retryAfterMs: Long,
): CrawlKind {
    // Nothing held is the only case that is genuinely a build, and it does not care how
    // recently something was attempted - an empty index is useless and waiting does not help.
    if (filesHeld <= 0L) return CrawlKind.BUILD

    // Something is held, so anything further is an update. The question left is whether one is
    // wanted now.
    val sinceComplete = now - lastCompleteAt
    val sinceAttempt = now - lastAttemptAt

    // An attempt that did not complete still counts as work done. Retrying it immediately is
    // what turned a tree bigger than one budget into a permanent crawl.
    if (lastAttemptAt > 0L && sinceAttempt < retryAfterMs) return CrawlKind.NONE

    if (lastCompleteAt == 0L) return CrawlKind.UPDATE
    return if (sinceComplete > staleAfterMs) CrawlKind.UPDATE else CrawlKind.NONE
}

/**
 * Whether a directory can be skipped on an update pass.
 *
 * The modification time of a directory moves when an entry is added to it or removed from it,
 * so a directory whose recorded time still matches the one on disk holds exactly the names it
 * held last time. Its children may have changed *contents*, and that does not matter here:
 * the index records names, sizes and times, and a child whose own size or time changed is
 * caught when that child is visited by the listing of its own parent.
 *
 * Zero is treated as unknown rather than as a time, because a filesystem that does not report
 * directory times would otherwise let every directory be skipped for ever.
 */
fun dirUnchanged(recordedMtime: Long, actualMtime: Long): Boolean =
    recordedMtime > 0L && actualMtime > 0L && recordedMtime == actualMtime
