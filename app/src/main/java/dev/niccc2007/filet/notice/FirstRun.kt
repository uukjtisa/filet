package dev.niccc2007.filet.notice

import android.content.Context
import dev.niccc2007.filet.log.FiletLog

/**
 * Whether this is a genuinely new install, and whether the welcome has been shown.
 *
 * ## Telling a first run from an upgrade
 *
 * By the package manager's own record, not by looking for data. An install that has never been
 * updated has `lastUpdateTime == firstInstallTime`; every upgrade moves `lastUpdateTime` on. A
 * reinstall after uninstalling starts both clocks again, which is right - it has no prior data
 * either. Looking for data instead fails both ways: onboarding writes its flag before the browser
 * is first composed, so a true first run already "has data", and an upgrade from a build that
 * never wrote a given key looks new.
 *
 * ## Where "once" lives
 *
 * `welcome.shown` in the `filet.notice` SharedPreferences, written the moment the dialogue is
 * dismissed. A process death after that cannot bring it back; a process death before it - the
 * dialogue on screen and the app killed - shows it again, which is correct, because it was never
 * seen to the end. An upgrade writes the flag too, without showing anything, so a later
 * reinstall-style clock quirk cannot surface it on somebody who has used the app for months.
 */
object FirstRun {

    /** Pure. */
    fun shouldWelcome(shown: Boolean, firstInstall: Long, lastUpdate: Long, pretendFirstRun: Boolean = false): Boolean {
        if (pretendFirstRun) return true
        if (shown) return false
        return firstInstall > 0 && lastUpdate == firstInstall
    }

    /** Pure. True when this launch is an upgrade and the flag should be settled silently. */
    fun isUpgrade(firstInstall: Long, lastUpdate: Long): Boolean = firstInstall > 0 && lastUpdate > firstInstall

    private const val PREFS = "filet.notice"
    private const val KEY = "welcome.shown"

    /**
     * Debug-only: `files/pretend-first-run` under the external app dir makes this launch a first
     * run without touching any data. Deleted once the welcome is dismissed, so it fires once.
     */
    const val PRETEND_FILE = "pretend-first-run"

    /** Queues the welcome when this is a first run. Call once the main screen exists. */
    fun check(context: Context, debug: Boolean) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val first = info?.firstInstallTime ?: 0L
        val last = info?.lastUpdateTime ?: 0L
        val pretend = debug && pretendFile(context)?.isFile == true
        val shown = sp.getBoolean(KEY, false)
        when {
            shouldWelcome(shown, first, last, pretend) -> {
                FiletLog.i("notice", "first run${if (pretend) " (debug override)" else ""}: queueing welcome")
                Notices.offer(NoticeKind.WELCOME)
            }
            !shown && isUpgrade(first, last) -> {
                sp.edit().putBoolean(KEY, true).apply()
                FiletLog.i("notice", "welcome declined: upgrade, not a first run")
            }
            else -> FiletLog.d("notice", "welcome declined: ${if (shown) "already shown" else "no install record"}")
        }
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        runCatching { pretendFile(context)?.delete() }
    }

    private fun pretendFile(context: Context) =
        runCatching { context.getExternalFilesDir(null)?.resolve(PRETEND_FILE) }.getOrNull()
}
