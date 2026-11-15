package dev.niccc2007.filet.locker

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Hears Filet leave the screen: the moment the last of its screens stops.
 *
 * Counted across every activity rather than read off one, because Filet opens the player, the
 * viewers and the picker as screens of their own, and going from the browser into the player is
 * not leaving. A turn of the phone stops one screen and starts the same one again; that is not
 * leaving either, which is what the configuration check is for - without it, every rotation
 * would lock the Locker.
 */
class LockerOnLeave(private val onLeave: () -> Unit) : Application.ActivityLifecycleCallbacks {

    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) onLeave()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
