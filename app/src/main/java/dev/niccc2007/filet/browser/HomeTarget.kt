package dev.niccc2007.filet.browser

import dev.niccc2007.filet.vfs.VPath

/**
 * What the Home tab opens.
 *
 * By default it is the overview - storage, what arrived, what you opened. It can be set to a
 * folder instead, from the three-dot menu, so a phone whose whole use is one working directory
 * lands there.
 *
 * Pure because two parts of it are decisions rather than plumbing:
 *
 *  - **A stored target can stop existing.** The folder is remembered as a path, and a path
 *    outlives the folder. Home is the screen that opens on launch, so a target that no longer
 *    resolves must fall back rather than open an error - a file manager whose first screen is
 *    a failure looks broken before it has done anything.
 *  - **The reminder fires exactly once in the app's life.** Not once per session and not once
 *    per folder: one pop-up, ever. "Did I already show this" is a question a boolean answers
 *    wrongly the moment it is reset by a reinstall-shaped code path.
 */
object HomeTarget {

    /** What Home should draw. */
    sealed interface Resolution {
        /** The built-in overview. */
        data object Overview : Resolution

        /** A folder the user chose. */
        data class Folder(val path: VPath) : Resolution
    }

    /**
     * Resolve the stored target.
     *
     * @param stored what was saved, or null for the default.
     * @param exists whether that path is still there. Asked as a function rather than assumed,
     *   because checking is a filesystem read and the caller decides when to pay for it.
     */
    fun resolve(stored: VPath?, exists: (VPath) -> Boolean): Resolution {
        if (stored == null) return Resolution.Overview
        return if (exists(stored)) Resolution.Folder(stored) else Resolution.Overview
    }

    /**
     * Whether setting a home folder should show the one-time reminder.
     *
     * The reminder says the choice is reversible and where to reverse it. It is worth showing
     * once because "Home is not what it was" is alarming and the way back is not obvious; it is
     * not worth showing twice, because by then the user knows.
     *
     * @param alreadyShown whether it has ever been shown before.
     * @param settingAFolder true when a folder is being set, false when Home is being reset
     *   back to the overview. Resetting is the thing the reminder was telling them about, so
     *   showing it then would be absurd.
     */
    fun shouldRemind(alreadyShown: Boolean, settingAFolder: Boolean): Boolean =
        settingAFolder && !alreadyShown

    /** The reminder's words, which name where the setting lives so it can be found again. */
    const val REMINDER_TITLE = "Home is now this folder"

    const val REMINDER_BODY =
        "Filet will open here instead of the overview. You can change it back any time in " +
            "Settings › Look and feel › Home screen."
}
