package dev.niccc2007.filet.update

import dev.niccc2007.filet.log.FiletLog

/**
 * Whether a posted notification can be seen, decided from what the system reports.
 *
 * Two of the ways this fails are invisible from the code: a channel created once and then turned
 * down or off by the user cannot be raised again by the app, and `notify()` succeeds against it
 * without complaint. So the importance is read back every time and the answer is logged, rather
 * than assumed from the importance the channel was created with.
 *
 * Importance values are `NotificationManager`'s: 0 NONE, 1 MIN, 2 LOW, 3 DEFAULT, 4 HIGH. Written
 * as numbers so this stays a pure function a JVM test can call.
 */
object NotifyGate {

    enum class Outcome(val posts: Boolean, val note: String) {
        SHOWN(true, "posted to a channel that alerts"),
        /** Posted, but lands silently in the shade - LOW or MIN. Somebody may never look. */
        QUIET(true, "posted, but the Updates channel is set to quiet"),
        NO_PERMISSION(false, "POST_NOTIFICATIONS is not granted (Android 13+)"),
        APP_OFF(false, "notifications are switched off for Filet"),
        CHANNEL_OFF(false, "the Updates channel is turned off"),
        FAILED(false, "the system refused the post"),
    }

    fun classify(sdk: Int, permissionGranted: Boolean, appEnabled: Boolean, channelImportance: Int?): Outcome = when {
        sdk >= 33 && !permissionGranted -> Outcome.NO_PERMISSION
        !appEnabled -> Outcome.APP_OFF
        channelImportance == 0 -> Outcome.CHANNEL_OFF
        // Null is a channel that could not be read back, which on 26+ only happens if creating it
        // failed; posting is still attempted and the result reported as quiet, not as shown.
        channelImportance == null || channelImportance < 3 -> Outcome.QUIET
        else -> Outcome.SHOWN
    }

    fun log(o: Outcome) {
        if (o == Outcome.SHOWN) FiletLog.i("update", "notification: ${o.note}")
        // Not an error the app can fix, but the one fact that explains "I was never told".
        else FiletLog.w("update", "notification: ${o.note}; the in-app sheet is the only notice")
    }
}
