package dev.niccc2007.filet.home

/**
 * When a burst of filesystem notifications should turn into one refresh.
 *
 * Nic: *"that tracker and new files tracked folders has a real problem, its not latest, it
 * takes time to update. we need to fix that so that even if its tracking a lot of shit the
 * update time is still fast."*
 *
 * Three things were wrong and this is the third. The watcher fired `index.crawl(...)` with a
 * four-second budget and only refreshed the feed *after* it returned, so the cheap update
 * queued behind the expensive one. Copying twenty files into a tracked folder fired twenty of
 * those. And the tracked list was handed to the watcher once at startup, so a folder tracked
 * afterwards was never watched at all.
 *
 * This part is the coalescing: wait for the noise to stop, but never wait forever.
 */

/** Long enough for a copy to finish writing, short enough to feel immediate. */
const val QUIET_MS = 250L

/** However busy the folder is, the feed is never more stale than this. */
const val MAX_WAIT_MS = 1_500L

/**
 * How long to wait before refreshing, given when the burst started and when it last ticked.
 *
 * @param sinceFirstChange milliseconds since the first unserviced change.
 * @param sinceLastChange milliseconds since the most recent one.
 * @return 0 to refresh now, otherwise how long to sleep before asking again.
 *
 * Two clocks, because either alone fails. Quiet-only starves under a long copy: something
 * writing every 100 ms would hold the refresh off indefinitely and the feed would show
 * nothing until it finished. Deadline-only fires mid-copy and shows half the files, then
 * fires again, and again.
 */
fun coalesceDelay(
    sinceFirstChange: Long,
    sinceLastChange: Long,
    quietMs: Long = QUIET_MS,
    maxWaitMs: Long = MAX_WAIT_MS,
): Long {
    if (sinceFirstChange >= maxWaitMs) return 0L
    if (sinceLastChange >= quietMs) return 0L
    val untilQuiet = quietMs - sinceLastChange
    val untilDeadline = maxWaitMs - sinceFirstChange
    return minOf(untilQuiet, untilDeadline).coerceAtLeast(1L)
}

/**
 * How many tracked folders to read at the same time.
 *
 * They were read one after another, so twelve tracked folders on a slow SD card meant twelve
 * round trips end to end. Bounded rather than unbounded: a phone's storage has a queue depth
 * measured in single digits, and firing fifty concurrent listings at it makes every one of
 * them slower.
 */
const val FEED_PARALLELISM = 6
