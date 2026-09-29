package dev.niccc2007.filet.apk

/**
 * What will happen if this APK is installed, worked out before it is attempted.
 *
 * ## Why this is the headline of the inspector
 *
 * The inspector used to print a full subject DN and a wrapped SHA-256 and leave the reader to
 * work out the only question they actually have: *will this install, and what does it do to
 * what is already there.* Those two fields answer it only if you know that Android refuses an
 * update whose signing certificate differs from the installed one, and that the only way
 * through is to uninstall first - which takes that app's data with it.
 *
 * That is a rule with a small number of outcomes, so it is a function with a test rather than a
 * paragraph somebody has to reason through at the wrong moment.
 *
 * ## The facts it needs are all real
 *
 * Everything here comes from `PackageManager`: the installed version and its signing
 * certificate, compared against the ones read out of the file. Nothing is estimated. An unknown
 * stays unknown - [Outlook.UNKNOWN_STATE] exists so a missing fact reads as a missing fact
 * rather than as a reassuring answer.
 */
enum class Outlook {
    /** Nothing by this package name is installed. A clean first install. */
    FRESH,

    /** A newer version replacing an older one, and the signatures agree. */
    UPDATE,

    /** The same version code that is already installed. */
    REINSTALL,

    /** An older version over a newer one. Android refuses this without an uninstall. */
    DOWNGRADE,

    /**
     * Something by this name is installed and was signed by a different key.
     *
     * The one that costs data, and the reason this whole file exists.
     */
    SIGNATURE_CLASH,

    /** The file carries no signature at all, so nothing will install it. */
    UNSIGNED,

    /** A fact was missing, so no claim is made. */
    UNKNOWN_STATE,
}

/**
 * @param installedVersionCode null when nothing by that package name is installed.
 * @param installedSha256 the installed package's signing certificate digest, or null when it
 *   could not be read. Compared case-insensitively because the two sources format it
 *   differently and a case mismatch reading as a key mismatch would be the worst possible bug
 *   in this file.
 */
data class InstallFacts(
    val signed: Boolean,
    val apkVersionCode: Long,
    val apkSha256: String?,
    val installedVersionCode: Long?,
    val installedSha256: String?,
)

data class InstallOutlook(
    val outlook: Outlook,
    val headline: String,
    val detail: String,
    /** True when this is the one worth a banner rather than a quiet line. */
    val severe: Boolean,
) {
    /**
     * Whether to put this in front of somebody at all.
     *
     * [Outlook.FRESH] is not shown, and the reason is a limit rather than a preference.
     * "Nothing by this name is installed" and "this package is not visible to Filet" arrive as
     * the same answer from `PackageManager`, so before package visibility was declared this row
     * announced that most of a phone's apps were not installed. Confidently, and wrongly.
     *
     * Visibility is declared now, but the row still earns nothing: not-installed is what a
     * reader already assumes about a file they are looking at, so the only honest version of
     * this surface says something when there IS something, and stays quiet otherwise.
     */
    val worthShowing: Boolean get() = outlook != Outlook.FRESH
}

object InstallOutlooks {

    fun of(f: InstallFacts): InstallOutlook = when {
        !f.signed -> InstallOutlook(
            Outlook.UNSIGNED,
            "This package is not signed.",
            "Android installs nothing unsigned. Sign it first - the inspector can, with a key " +
                "it generates for you.",
            severe = true,
        )

        f.installedVersionCode == null -> InstallOutlook(
            Outlook.FRESH,
            "Not installed yet.",
            "Nothing by this package name is on the device, so this is a first install and " +
                "there is nothing it can replace.",
            severe = false,
        )

        // Checked BEFORE the version comparison. A signature clash is fatal whichever way the
        // version numbers run, and reporting it as an ordinary update would be the one mistake
        // here that costs somebody their data.
        f.apkSha256 == null || f.installedSha256 == null -> InstallOutlook(
            Outlook.UNKNOWN_STATE,
            "Cannot tell whether the signing keys match.",
            "One of the two certificates could not be read. If they differ, Android will refuse " +
                "the install and the only way through is to uninstall first.",
            severe = true,
        )

        !f.apkSha256.equals(f.installedSha256, ignoreCase = true) -> InstallOutlook(
            Outlook.SIGNATURE_CLASH,
            "A different key signed the installed copy.",
            "Android will refuse this. Installing it means uninstalling the current one first, " +
                "and that takes its data with it.",
            severe = true,
        )

        f.apkVersionCode > f.installedVersionCode -> InstallOutlook(
            Outlook.UPDATE,
            "An update to what is installed.",
            "Same signing key, higher version. It replaces the installed copy and keeps its data.",
            severe = false,
        )

        f.apkVersionCode == f.installedVersionCode -> InstallOutlook(
            Outlook.REINSTALL,
            "The same version is already installed.",
            "Installing it again replaces the files and keeps the data.",
            severe = false,
        )

        else -> InstallOutlook(
            Outlook.DOWNGRADE,
            "Older than what is installed.",
            "Android refuses a downgrade. It would have to be uninstalled first, which takes " +
                "its data with it.",
            severe = true,
        )
    }
}
