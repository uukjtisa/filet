package dev.niccc2007.filet.desktop.mount

/**
 * Platform error numbers turned into sentences.
 *
 * Decision: mount through the platform API and branch on the number it returns, never shell out
 * and read the text. `net use` prints its errors in the display language of the machine, so a
 * parser written against English text turns every failure on a German or Filipino Windows into
 * "something went wrong" - and it works perfectly on the machine it was written on, which is why
 * it never gets caught. A number is the same in every language.
 *
 * Pure tables, so each mapping is tested without a network.
 */
object WinNetErrors {
    const val NO_ERROR = 0
    const val ACCESS_DENIED = 5
    const val BAD_NETPATH = 53
    const val UNEXP_NET_ERR = 59
    const val NETNAME_DELETED = 64
    const val BAD_NET_NAME = 67
    const val ALREADY_ASSIGNED = 85
    const val INVALID_PASSWORD = 86
    const val BAD_DEVICE = 1200
    const val CONNECTION_UNAVAIL = 1201
    const val DEVICE_ALREADY_REMEMBERED = 1202
    const val NO_NET_OR_BAD_PATH = 1203
    const val EXTENDED_ERROR = 1208
    const val SESSION_CREDENTIAL_CONFLICT = 1219
    const val NO_NETWORK = 1222
    const val CANCELLED = 1223
    const val NETWORK_UNREACHABLE = 1231
    const val NOT_AUTHENTICATED = 1244
    const val LOGON_FAILURE = 1326
    const val NOT_CONNECTED = 2250
    const val OPEN_FILES = 2401
    const val DEVICE_IN_USE = 2404

    fun message(code: Int): String = when (code) {
        NO_ERROR -> "Done."
        ACCESS_DENIED -> "The share refused access. Check the access code, and that the share is still running."
        BAD_NETPATH, NO_NET_OR_BAD_PATH, NETWORK_UNREACHABLE ->
            "The device could not be reached. Check it is on the same Wi-Fi and that Filet is still sharing."
        BAD_NET_NAME ->
            "The device answered, but not with that share. The access code may be wrong, or the share was stopped. " +
                "If every mount fails this way, the WebClient service is not running."
        UNEXP_NET_ERR, NETNAME_DELETED -> "The connection dropped part-way. The device may have gone to sleep."
        ALREADY_ASSIGNED, DEVICE_IN_USE -> "That drive letter is already in use."
        DEVICE_ALREADY_REMEMBERED -> "Windows still remembers an old drive on that letter. Disconnect it first."
        BAD_DEVICE -> "That is not a drive letter Windows will accept."
        INVALID_PASSWORD, LOGON_FAILURE, NOT_AUTHENTICATED ->
            "The share asked for a sign-in this tool cannot give. The access code goes in the address, not in a password."
        SESSION_CREDENTIAL_CONFLICT ->
            "Windows is already connected to this device under different credentials. Disconnect the other drive first."
        NO_NETWORK, CONNECTION_UNAVAIL -> "This computer is not on a network right now."
        CANCELLED -> "Cancelled."
        NOT_CONNECTED -> "That drive was already disconnected."
        OPEN_FILES -> "Files are still open on that drive. Close them, or unmount anyway."
        EXTENDED_ERROR -> "The WebDAV redirector refused without saying why (Windows error 1208)."
        else -> "Windows refused with error $code."
    }
}

/**
 * macOS: `NetFSMountURLSync` returns an `errno` or one of NetFS's own negative codes.
 *
 * The negative ones are from `NetFS/NetFSErrors.h`; `-128` is Carbon's `userCanceledErr`, which
 * NetFS passes through when an authentication sheet is dismissed.
 */
object NetFsErrors {
    fun message(code: Int): String = when (code) {
        0 -> "Done."
        1, 13 -> "macOS refused permission to mount there."                       // EPERM, EACCES
        2 -> "The share was not found. The access code may be wrong."             // ENOENT
        16 -> "That folder is busy."                                              // EBUSY
        17 -> "Something is already mounted there."                               // EEXIST
        51, 65 -> "The device could not be reached. Check it is on the same Wi-Fi." // ENETUNREACH, EHOSTUNREACH
        60 -> "The device stopped answering."                                     // ETIMEDOUT
        61 -> "The device refused the connection. Filet may have stopped sharing." // ECONNREFUSED
        80 -> "The share asked for a sign-in this tool cannot give."              // EAUTH
        -128 -> "Cancelled."
        -5045, -5046, -5999 -> "The share asked for a sign-in this tool cannot give."
        -5998 -> "The device is not offering any shares."
        -5997, -5996 -> "The device speaks a version of WebDAV macOS will not mount."
        else -> "macOS refused with error $code."
    }
}

/**
 * Linux has no mount API for WebDAV: `davfs2` and `gio` are programs, not libraries, and the
 * kernel's `mount(2)` cannot take a `davfs` source without the helper. So the decision there is
 * the reverse of Windows - check every precondition from the JVM first, where the answer is a
 * real branch, so the only failure left for the helper is the network one, and branch on its exit
 * status. Its text is captured with `LC_ALL=C` and shown as detail, never parsed.
 */
object LinuxMountErrors {
    /** `mount(8)` exit statuses, which `mount.davfs` follows; `gio` exits 1 for anything. */
    fun message(tool: String, status: Int): String = when (status) {
        0 -> "Done."
        1 -> if (tool == "gio") "gio could not mount the share. Check the device is reachable and the code is right."
        else "mount.davfs refused: wrong permissions or no matching line in /etc/fstab."
        2 -> "$tool hit a system error."
        32 -> "The mount failed. Check the device is reachable and the code is right."
        else -> "$tool exited with status $status."
    }
}

/**
 * The answer a share gives to a PROPFIND before anything is mounted.
 *
 * Probing first is what makes the Windows errors above rarely matter. The redirector folds "wrong
 * code", "share stopped" and "WebClient service stopped" into the same `ERROR_BAD_NET_NAME`, but
 * the share itself answers a wrong code with 403, an absent one with 404 and success with 207 -
 * distinct, and checked in a second rather than after a twenty-second redirector timeout.
 */
object ProbeErrors {
    fun message(status: Int): String? = when (status) {
        207, 200 -> null
        400 -> "That address is not a Filet share."
        401 ->"The share asked for a sign-in this tool cannot give."
        403 -> "Wrong access code. It is shown on the device's sharing screen."
        404 -> "The device answered, but has no share at that address."
        405, 501 -> "That address is not a WebDAV share."
        else -> "The share answered with HTTP $status."
    }

    const val UNREACHABLE = "The device could not be reached. Check it is on the same Wi-Fi and still sharing."
}
