package dev.niccc2007.filet.update

import android.content.Context
import dev.niccc2007.filet.log.FiletLog

/**
 * Every reason the unasked-for update check can stay quiet, named.
 *
 * Bug identified here, and it is why the popup could look broken for nine releases with nobody
 * able to say whether it was: every gate returned a bare `false` or an early `return`, and none of
 * them said which one fired. A build running ahead of the newest release - every development
 * install, permanently - is correctly told nothing, and that was indistinguishable from a check
 * that never ran, a throttle stuck in the future, or a dismissal that never expired. Now each
 * decline carries its gate and is written to the log as one line.
 */
enum class Gate {
    /** The f-droid flavour, which may not update itself. */
    UPDATER_OFF,
    /** "Never remind me" was chosen. Check for updates still works. */
    NOTIFICATIONS_OFF,
    /** Asked GitHub within the interval, and this is not a cold launch. */
    THROTTLED,
    /** A check failed moments ago in this run; waiting before trying again. */
    RETRYING,
    /** GitHub did not answer. */
    UNREACHABLE,
    /** GitHub answered with no app release at all. */
    NO_RELEASES,
    /** The newest app release is not newer than what is running. */
    NOT_NEWER,
    /** "Skip this version" was chosen for exactly this version. */
    SKIPPED,
    /** "In a day / 3 days / a week" for this version, and the time has not passed. */
    POSTPONED,
    /** "Later" for this version, said during this run. */
    LATER_THIS_RUN,
}

sealed interface UpdateVerdict {
    data class Ask(val release: Release) : UpdateVerdict
    data class Decline(val gate: Gate, val detail: String) : UpdateVerdict
}

/**
 * The quiet check's whole decision, in two halves around the one network call.
 *
 * Pure apart from [log] and [prepare], so every path - including the ones that only happen with
 * a particular clock and a particular stored answer - is a unit test.
 */
object UpdateGate {

    /** Before asking GitHub: null to go ahead, otherwise the gate that stops it. */
    fun beforeNetwork(
        updaterEnabled: Boolean,
        state: ReminderState,
        now: Long,
        coldLaunch: Boolean,
        lastAttempt: Long,
    ): UpdateVerdict.Decline? {
        if (!updaterEnabled) return UpdateVerdict.Decline(Gate.UPDATER_OFF, "this flavour does not self-update")
        if (state.notificationsOff) return UpdateVerdict.Decline(Gate.NOTIFICATIONS_OFF, "never-remind is on")
        if (!shouldCheck(state, now, coldLaunch = coldLaunch)) {
            return UpdateVerdict.Decline(Gate.THROTTLED, "last checked ${(now - state.lastCheckedAt) / 60_000} min ago, warm resume")
        }
        // Only a recent attempt in the PAST holds a retry off. A clock set backwards during a
        // run made `now - lastAttempt` negative, which is always "less than two minutes", so the
        // check stayed off until the clock caught up with where it had been.
        val since = now - lastAttempt
        if (lastAttempt > 0 && since in 0 until RETRY_AFTER_FAILURE_MS) {
            return UpdateVerdict.Decline(Gate.RETRYING, "last attempt ${since / 1000}s ago")
        }
        return null
    }

    /** After asking GitHub: offer [result]'s release, or say exactly why not. */
    fun afterNetwork(
        result: Result<Release?>,
        installed: Version,
        state: ReminderState,
        now: Long,
        launchId: Long,
    ): UpdateVerdict {
        result.exceptionOrNull()?.let {
            return UpdateVerdict.Decline(Gate.UNREACHABLE, it.message ?: it.javaClass.simpleName)
        }
        val release = result.getOrNull()
            ?: return UpdateVerdict.Decline(Gate.NO_RELEASES, "no app release published")
        val gate = promptGate(release.version, installed, state, now, launchId)
            ?: return UpdateVerdict.Ask(release)
        return UpdateVerdict.Decline(gate, "latest ${release.version} (${release.tag}), installed $installed")
    }

    /** One line per decision, so a silent popup always has a reason on record. */
    fun log(verdict: UpdateVerdict) {
        when (verdict) {
            is UpdateVerdict.Ask -> FiletLog.i(TAG, "offering ${verdict.release.version} over ${Updater.installed}")
            is UpdateVerdict.Decline -> FiletLog.i(TAG, "declined at ${verdict.gate}: ${verdict.detail}")
        }
    }

    /**
     * The version the gate compares against.
     *
     * A development install runs one version ahead of the newest release, so on the only phone
     * that tests the updater nothing is ever newer and the popup cannot be seen. [pretend] lets a
     * DEBUG build claim an older version, read from a file pushed over adb, so the path can be
     * exercised without touching the app's data. Ignored in a release build, and ignored when it
     * does not parse - a typo must not turn into version 0.0.0 and an offer of everything.
     */
    fun installedFor(real: Version, pretend: String?, debug: Boolean): Version {
        if (!debug || pretend.isNullOrBlank()) return real
        val p = pretend.trim()
        if (!Regex("""^v?\d+\.\d+(\.\d+)?$""").matches(p)) return real
        return Version.parse(p)
    }

    /** Reads the debug-only override. `files/pretend-version.txt` under the external app dir. */
    fun prepare(context: Context, debug: Boolean) {
        if (!debug) return
        val f = runCatching { context.getExternalFilesDir(null)?.resolve(PRETEND_FILE) }.getOrNull()
        Updater.pretend = f?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        Updater.pretend?.let { FiletLog.w(TAG, "debug override: installed version reads as ${Updater.installed}") }
    }

    const val PRETEND_FILE = "pretend-version.txt"
    private const val TAG = "update"
}
