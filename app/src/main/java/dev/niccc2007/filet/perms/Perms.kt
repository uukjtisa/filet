package dev.niccc2007.filet.perms

/**
 * Every permission Filet uses, what it is for, and how it is obtained.
 *
 * ## Why this is a catalogue and not a handful of `checkSelfPermission` calls
 *
 * The reported fault: the Storage access card offered **Grant all files** when all-files
 * access was already granted. A control that offers something you already have is worse than
 * no control, because it makes you doubt that the grant took. The cause was that nothing in
 * the app ever asked what it actually held - it only ever asked for more.
 *
 * So every permission is an entry with a way to READ its state, and the screen is built from
 * the reading rather than from a guess.
 *
 * ## The three shapes, which is the thing that makes this hard
 *
 * Android does not have one kind of permission, it has three, and they cannot be granted the
 * same way. Lumping them together is why "just ask for all of them" does not work:
 *
 *  - [Kind.INSTALL] — granted when the app is installed and never revocable. Internet, Wi-Fi
 *    state, multicast. Listed so the list is honest about everything Filet holds, with no
 *    button, because there is nothing to press.
 *  - [Kind.RUNTIME] — the ordinary dialogue. Notifications, and legacy storage below API 30.
 *    These can be batched into one request.
 *  - [Kind.SPECIAL] — a full-screen Settings page per permission, one at a time, each one
 *    leaving the app. All-files access, install-unknown-apps, battery exemption. **These
 *    cannot be batched and no API exists to request several**, which is the honest answer to
 *    "prompt them all at startup": the best possible version is a guided sequence.
 *
 * ## Revoking
 *
 * There is no API for it, for any of the three. The only thing an app can do is open its own
 * entry in system Settings, which is where the switches are. [Entry.revocable] records which
 * ones that page can actually turn off, so the screen does not send somebody to a page where
 * the thing they wanted is not.
 */
object Perms {

    enum class Kind {
        /** Granted at install. Nothing to ask for and nothing to revoke. */
        INSTALL,

        /** The ordinary runtime dialogue. Can be asked for several at a time. */
        RUNTIME,

        /** A Settings page of its own. One at a time, and the app goes to the background. */
        SPECIAL,
    }

    /**
     * @param id the manifest permission, or a made-up key for the ones that are not manifest
     *   permissions at all - the battery exemption is a device setting, not a permission, and
     *   pretending otherwise in the data would mean pretending in the code.
     * @param needed what stops working without it, in the words the screen uses. Not "allows
     *   Filet to..." - the question somebody has in Settings is what they lose, not what the
     *   app gains.
     * @param minSdk the API this first exists at. Below it, the entry is not shown at all,
     *   rather than shown as permanently granted, because a permission that does not exist on
     *   this phone is noise.
     * @param maxSdk the last API it means anything on. Legacy storage is superseded at 30.
     */
    data class Entry(
        val id: String,
        val label: String,
        val needed: String,
        val kind: Kind,
        val minSdk: Int = 1,
        val maxSdk: Int = Int.MAX_VALUE,
        val revocable: Boolean = true,
        /** Filet still works without it; the screen says so rather than nagging. */
        val optional: Boolean = true,
    )

    const val ALL_FILES = "android.permission.MANAGE_EXTERNAL_STORAGE"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val INSTALL_APPS = "android.permission.REQUEST_INSTALL_PACKAGES"
    const val READ_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val WRITE_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"

    /** Not a permission. A device setting, keyed like one so the screen can list it. */
    const val BATTERY = "filet.batteryExemption"

    val CATALOGUE: List<Entry> = listOf(
        Entry(
            ALL_FILES, "All files access",
            "Without it Filet can only see folders you grant one at a time.",
            Kind.SPECIAL, minSdk = 30,
        ),
        Entry(
            BATTERY, "Run without battery limits",
            "Without it Android may stop a share or a long copy once the screen is off.",
            Kind.SPECIAL, minSdk = 23,
        ),
        Entry(
            NOTIFICATIONS, "Notifications",
            "Without it there is no progress for a copy, no player controls and no update notice.",
            Kind.RUNTIME, minSdk = 33,
        ),
        Entry(
            INSTALL_APPS, "Install apps",
            "Without it tapping an APK opens the inspector but cannot install it.",
            Kind.SPECIAL, minSdk = 26,
        ),
        Entry(
            READ_STORAGE, "Read storage",
            "The old way of reaching shared storage. Superseded by all-files access.",
            Kind.RUNTIME, maxSdk = 29,
        ),
        Entry(
            WRITE_STORAGE, "Write storage",
            "The old way of writing to shared storage. Superseded by all-files access.",
            Kind.RUNTIME, maxSdk = 29,
        ),
        Entry(
            "android.permission.INTERNET", "Internet",
            "Reaching a network drive, and looking for an update.",
            Kind.INSTALL, revocable = false,
        ),
        Entry(
            "android.permission.ACCESS_NETWORK_STATE", "Network state",
            "Noticing that the network changed, so a share re-announces itself.",
            Kind.INSTALL, revocable = false,
        ),
        Entry(
            "android.permission.ACCESS_WIFI_STATE", "Wi-Fi state",
            "Finding this phone's address to put on a share link.",
            Kind.INSTALL, revocable = false,
        ),
        Entry(
            "android.permission.CHANGE_WIFI_MULTICAST_STATE", "Multicast",
            "Discovering other Filets on the network.",
            Kind.INSTALL, revocable = false,
        ),
        Entry(
            "android.permission.QUERY_ALL_PACKAGES", "See installed apps",
            "Listing which app can open a file, and reading an APK you already have.",
            Kind.INSTALL, revocable = false,
        ),
    )

    /** The entries that mean anything on this API level. */
    fun shownOn(sdk: Int): List<Entry> =
        CATALOGUE.filter { sdk >= it.minSdk && sdk <= it.maxSdk }

    /**
     * The order to walk somebody through the ones they have not granted.
     *
     * Runtime first, because they are one dialogue each and never leave the app, so the
     * sequence gets its cheap wins in before it starts bouncing to Settings. Then the
     * specials, most useful first. Install-time entries are never in the queue - there is
     * nothing to ask for.
     *
     * This is the honest version of "prompt them all at startup". Android has no API to
     * request several special permissions, and each one backgrounds the app, so a batch is
     * not available at any price; a queue that resumes where it left off is.
     */
    fun requestQueue(sdk: Int, granted: Set<String>): List<Entry> {
        val outstanding = shownOn(sdk).filter { it.kind != Kind.INSTALL && it.id !in granted }
        return outstanding.sortedBy { if (it.kind == Kind.RUNTIME) 0 else 1 }
    }

    /** The one-line summary at the top of the section. */
    fun summary(sdk: Int, granted: Set<String>): String {
        val shown = shownOn(sdk)
        val askable = shown.filter { it.kind != Kind.INSTALL }
        val have = askable.count { it.id in granted }
        return when {
            askable.isEmpty() -> "${shown.size} granted at install."
            have == askable.size -> "All $have granted, plus ${shown.size - have} held since install."
            else -> "$have of ${askable.size} granted. The rest are optional; what each one " +
                "costs is below."
        }
    }
}
