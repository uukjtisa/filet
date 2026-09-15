package dev.niccc2007.filet.nearby

/**
 * Whether to try starting the sharing server, and when to stop trying.
 *
 * ## Why it looped
 *
 * `NearbyService.onStartCommand` ended with `START_STICKY`, which asks the platform to bring
 * the service back if it dies. The first `startForegroundCompat(null)` was wrapped in
 * `runCatching`; the second one, `startForegroundCompat(graph.nearby.state.value)`, was not.
 * So if building that notification threw, the service crashed - and because the HTTP server
 * object lives in the **app** process rather than the service, it was still running when the
 * platform restarted the service, which took the same branch and threw again. Nothing in that
 * cycle ever gave up, and force-stopping the app was the only way out. "Rare and hard to
 * recreate" fits exactly: it needs the notification path to fail, which depends on state.
 *
 * ## Both halves, because they fail differently
 *
 * An attempt limit stops a loop that has started. A stop control ends one that is
 * already running. This object is the first; the second is a Stop action that no longer
 * depends on the server agreeing that it is up.
 *
 * The counting is here, away from Android, because "how many failures is too many" is a rule
 * that can be wrong quietly - too low and a slow Wi-Fi handover looks like a broken feature,
 * too high and the only escape is force-stopping again.
 */
object ServerStartPolicy {

    /**
     * Consecutive failures before Filet stops trying by itself.
     *
     * Three rather than one: the common failure is a network that is mid-handover, and that
     * genuinely succeeds on a retry. Three rather than ten: past the second failure the cause
     * is almost never transient, and a person watching a button do nothing ten times has
     * already decided the app is broken.
     */
    const val MAX_ATTEMPTS = 3

    /**
     * How long before a failure stops counting against the next attempt.
     *
     * Without this the counter is permanent for the life of the process: three failures on a
     * train this morning would refuse to start this afternoon on working Wi-Fi, with no way to
     * clear it but killing the app - the very thing this exists to stop.
     */
    const val COOLDOWN_MS = 60_000L

    sealed interface Decision {
        /** Go. */
        data object Start : Decision

        /** Already up; pressing Start again is not an error, it is a no-op. */
        data object AlreadyRunning : Decision

        /** Do not try. [why] is shown to the user and must say what to do next. */
        data class Refuse(val why: String) : Decision
    }

    /**
     * @param running whether the server is up right now.
     * @param blockedReason a precondition failure that is already a sentence, or null.
     * @param consecutiveFailures failed starts in a row, not cleared by success.
     * @param lastFailureAt when the most recent failure happened, 0 if there has never been one.
     * @param now the clock.
     */
    fun decide(
        running: Boolean,
        blockedReason: String?,
        consecutiveFailures: Int,
        lastFailureAt: Long,
        now: Long,
    ): Decision {
        if (running) return Decision.AlreadyRunning
        // A precondition that is already known beats the counter: telling somebody with Wi-Fi
        // off that Filet has "stopped trying" hides the thing they can actually fix.
        if (blockedReason != null) return Decision.Refuse(blockedReason)

        val stale = lastFailureAt > 0L && now - lastFailureAt >= COOLDOWN_MS
        val failures = if (stale) 0 else consecutiveFailures
        if (failures >= MAX_ATTEMPTS) {
            return Decision.Refuse(
                "Sharing would not start after $MAX_ATTEMPTS tries. Check your Wi-Fi and try " +
                    "again in a minute — Filet has stopped retrying on its own so it cannot " +
                    "loop in the background.",
            )
        }
        return Decision.Start
    }

    /** The failure count after an attempt, given whether it worked. */
    fun countAfter(consecutiveFailures: Int, succeeded: Boolean): Int =
        if (succeeded) 0 else consecutiveFailures + 1
}
