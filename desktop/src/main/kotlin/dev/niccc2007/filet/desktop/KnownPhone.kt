package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.desktop.mount.DavTarget

/**
 * A phone share this PC has mounted at least once, saved so its row outlives the phone leaving
 * the network, a restart of this tool, and Windows reattaching the drive at login.
 *
 * The code is saved with it. Windows already keeps the same code inside the remembered mapping's
 * path under `HKCU\Network`, so this file holds nothing the OS does not, and without it the row
 * could not be mounted again without asking for the code a second time.
 */
data class KnownPhone(
    /** The row's identity: the device's TXT `id` and the share name, or the address when it sent no id. */
    val id: String,
    /** The share's name as the phone announces it. */
    val name: String,
    /** What this PC calls the drive. Starts as [name]; Rename changes only this. */
    val label: String,
    val host: String,
    val port: Int,
    val code: String,
    val secure: Boolean = false,
    val base: String = "/a/",
    val deviceId: String = "",
    /** The letter it was last mounted on, Windows only. Offered first the next time. */
    val letter: Char? = null,
) {
    val target: DavTarget get() = DavTarget(host, port, secure, base, code)
}
