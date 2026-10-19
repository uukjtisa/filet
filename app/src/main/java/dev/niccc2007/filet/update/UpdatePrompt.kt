package dev.niccc2007.filet.update

/**
 * When to tell somebody an update exists, and when to shut up about it.
 *
 * All of this is arithmetic over four stored values, so it lives here as pure functions with
 * tests rather than inside a notification callback. An update nag that fires when it was
 * silenced is the most annoying possible bug and the hardest to reproduce by hand: it needs a
 * particular clock, a particular stored state, and a particular release.
 *
 * The rule that matters most is the one at the bottom of [shouldPrompt]: **a silence is a
 * silence about a version, not about updating.** Somebody who said "remind me in a week" about
 * 0.2.0 still hears about 0.3.0 the day it lands, because the thing they postponed no longer
 * exists. Carrying the silence forward would mean one "later" tap could hide every future
 * release, which is how an updater quietly stops working.
 */

/** What the user chose when the update prompt asked. */
enum class RemindChoice {
    /**
     * Close it. It comes back the next time the app starts.
     *
     * Deliberately NOT "wait zero milliseconds". What The design calls for was to close it and be
     * asked again after opening the app, and that is a LAUNCH, not a clock reading. Expressed as a
     * clock it either fires again a millisecond later in the same session, or needs an
     * invented duration nobody asked for. So it stores the launch it was said in.
     */
    LATER,
    IN_A_DAY,
    IN_THREE_DAYS,
    IN_A_WEEK,

    /** Not this version. A newer one still speaks up. */
    SKIP_VERSION,

    /** Stop telling me. Reversible in Settings, and Check for updates still works. */
    NEVER,
}

/** How long each postponement lasts. Public so the UI can label the buttons from one source. */
val RemindChoice.delayMs: Long
    get() = when (this) {
        RemindChoice.LATER -> 0L
        RemindChoice.IN_A_DAY -> DAY_MS
        RemindChoice.IN_THREE_DAYS -> 3 * DAY_MS
        RemindChoice.IN_A_WEEK -> 7 * DAY_MS
        RemindChoice.SKIP_VERSION, RemindChoice.NEVER -> 0L
    }

const val DAY_MS = 24L * 60L * 60L * 1000L

/** How long to wait between asking GitHub. */
const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

/**
 * How long to wait before asking again after a check that did not reach GitHub.
 *
 * Short, because a failed check is not an answer. It used to consume the whole twelve hours,
 * so one resume without a connection meant nothing was said until the next day.
 */
const val RETRY_AFTER_FAILURE_MS = 2L * 60L * 1000L

/**
 * Everything persisted about the nagging. One object so a test can state a whole situation.
 *
 * @param silencedUntil epoch millis before which nothing is shown. 0 means nothing is pending.
 * @param silencedLaunch the launch id "Later" was tapped in. Not persisted across a restart -
 *   that is the entire point of it.
 * @param silencedVersion the version [silencedUntil], [silencedLaunch] and [skippedVersion]
 *   refer to. A different version ignores all three, which is the rule this file exists to
 *   enforce.
 * @param skippedVersion a version the user said they did not want. Empty when none.
 * @param notificationsOff the user asked never to be told. Nothing is posted; the About page's
 *   Check for updates button is unaffected, because that one is them asking.
 * @param lastCheckedAt epoch millis of the last time GitHub was asked, successfully or not.
 */
data class ReminderState(
    val silencedUntil: Long = 0L,
    val silencedLaunch: Long = 0L,
    val silencedVersion: String = "",
    val skippedVersion: String = "",
    val notificationsOff: Boolean = false,
    val lastCheckedAt: Long = 0L,
)

/**
 * Should the app tell the user about [available] right now?
 *
 * @param available the newest published version.
 * @param installed the version running.
 * @param state what the user has already said.
 * @param now epoch millis.
 */
fun shouldPrompt(
    available: Version,
    installed: Version,
    state: ReminderState,
    now: Long,
    launchId: Long = 0L,
): Boolean = promptGate(available, installed, state, now, launchId) == null

/**
 * Which rule stops [available] being offered, or null when nothing does.
 *
 * The same rules as [shouldPrompt], which is now defined through this, so the reason that gets
 * logged and the decision that gets made cannot drift apart. A bare boolean is what let a
 * correct "nothing newer" and a broken check look identical from outside for nine releases.
 */
fun promptGate(
    available: Version,
    installed: Version,
    state: ReminderState,
    now: Long,
    launchId: Long = 0L,
): Gate? {
    if (state.notificationsOff) return Gate.NOTIFICATIONS_OFF
    if (available <= installed) return Gate.NOT_NEWER

    val version = available.toString()
    // Both silences are scoped to the version they were made about. A postponement of 0.2.0
    // says nothing about 0.3.0 - see the file comment.
    if (state.skippedVersion == version) return Gate.SKIPPED
    if (state.silencedVersion != version) return null
    if (now < state.silencedUntil) return Gate.POSTPONED
    // "Later", said during this same run of the app.
    if (launchId != 0L && state.silencedLaunch == launchId) return Gate.LATER_THIS_RUN
    return null
}

/**
 * Should GitHub be asked at all, or was it asked recently enough?
 *
 * ## Why a deliberate launch ignores the interval
 *
 * Reported as the update notice never appearing any more, even on opening the app fresh - and
 * the interval is most of why. Filet holds a foreground service while music plays or a share
 * is hosted, so its PROCESS routinely lives for days; the check runs on resume
 * and is then throttled to twelve hours, which means somebody who opens the app ten times a
 * day is told at most twice and never at a moment they would connect with having opened it.
 *
 * Worse, the app cannot tell the two situations apart from the process alone. Swiping it out of
 * the task switcher and opening it again is, to the process, indistinguishable from never
 * having left - so "I just opened the app and it did not check" was a correct description of
 * the behaviour.
 *
 * A cold launch - a new Activity task, which is what swiping away and reopening produces - is
 * therefore allowed through regardless. It happens a handful of times a day at most, it costs
 * one request, and it is exactly the moment a person expects to be told.
 *
 * @param coldLaunch true on the first check of a newly created task.
 */
fun shouldCheck(
    state: ReminderState,
    now: Long,
    intervalMs: Long = CHECK_INTERVAL_MS,
    coldLaunch: Boolean = false,
): Boolean {
    if (state.notificationsOff) return false
    if (coldLaunch) return true
    // A clock that went backwards (timezone, manual set, a reboot before NTP) must not lock the
    // check out until the future catches up.
    if (now < state.lastCheckedAt) return true
    return now - state.lastCheckedAt >= intervalMs
}

/**
 * Fold the user's answer into the stored state.
 *
 * @param version the version they answered *about*. Stored with the silence so a later release
 *   is not covered by it.
 */
fun applyChoice(
    choice: RemindChoice,
    version: String,
    now: Long,
    state: ReminderState,
    launchId: Long = 0L,
): ReminderState = when (choice) {
    RemindChoice.NEVER -> state.copy(notificationsOff = true)

    RemindChoice.SKIP_VERSION -> state.copy(
        skippedVersion = version,
        // Clear any pending silence for it too, so the two never disagree.
        silencedUntil = 0L,
        silencedLaunch = 0L,
        silencedVersion = "",
    )

    RemindChoice.LATER -> state.copy(
        silencedLaunch = launchId,
        silencedVersion = version,
        silencedUntil = 0L,
    )

    else -> state.copy(
        silencedUntil = now + choice.delayMs,
        silencedLaunch = 0L,
        silencedVersion = version,
    )
}

/** Turning notifications back on. Clears the skip too, or the setting would look broken. */
fun reenable(state: ReminderState): ReminderState = state.copy(
    notificationsOff = false,
    skippedVersion = "",
    silencedUntil = 0L,
    silencedLaunch = 0L,
    silencedVersion = "",
)

/** Human wording for how long a postponement lasts. One source, so a label cannot drift. */
fun RemindChoice.label(): String = when (this) {
    RemindChoice.LATER -> "Later"
    RemindChoice.IN_A_DAY -> "In a day"
    RemindChoice.IN_THREE_DAYS -> "In 3 days"
    RemindChoice.IN_A_WEEK -> "In a week"
    RemindChoice.SKIP_VERSION -> "Skip this version"
    RemindChoice.NEVER -> "Never remind me"
}
