package dev.niccc2007.filet.signet

import android.content.Context

/**
 * Whether the introduction has been seen (TEMPLATE.md §7.3).
 *
 * Deliberately trivial storage: one boolean in SharedPreferences. Written when the screen
 * finishes by either route - Skip and Done both mean "do not show this again" - so nobody can
 * be trapped in onboarding by taking the exit that seems like it does not count.
 */
private const val PREFS = "filet.signet"
private const val KEY_DONE = "onboarding.done"

fun onboardingComplete(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

fun markOnboardingComplete(ctx: Context) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
}
