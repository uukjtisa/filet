package dev.niccc2007.filet.nearby

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether Filet may reach this network's other devices at all: look for them, announce itself to
 * them, connect to them.
 *
 * From Android 17, for an app that targets it (Filet does), the local network is a permission -
 * Nearby devices, `ACCESS_LOCAL_NETWORK` - and it starts refused. Below that it is simply there.
 *
 * Bug identified here (issue #38): nothing asked for it, and network service discovery without
 * it does not fail. It raises the system's own "Choose a device to connect" picker, over the app,
 * every time a listener starts. Filet started one when it came to the screen, one per server type
 * on Remotes, and rebuilt the beacon's every 45 seconds, so on a Pixel the picker came back as
 * fast as it was dismissed. Every listener and every announcement now asks here first, and only
 * Remotes and Nearby ask the person for the permission, with the platform's ordinary prompt.
 */
object LocalNetwork {

    /** The permission, by name: Android 17's, so spelled out rather than read from an SDK field. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The first Android that asks: 17. */
    const val ASKS_FROM = 37

    /** Whether Android [sdk] asks for the local network at all. */
    fun asks(sdk: Int): Boolean = sdk >= ASKS_FROM

    /** The decision, pure: an Android [sdk] that does not ask, or the permission [granted]. */
    fun allowed(sdk: Int, granted: Boolean): Boolean = !asks(sdk) || granted

    /** Whether the network may be used now, on this phone. */
    fun allowed(context: Context): Boolean = allowed(
        Build.VERSION.SDK_INT,
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
    )
}
