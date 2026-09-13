package dev.niccc2007.filet.milestones

import android.content.Context
import java.io.File

/**
 * Where a milestone gate leaves evidence, and where it hands files to the development host.
 *
 * `Download/` rather than the app's own external files directory, for one reason: the test
 * runner **uninstalls the app when the suite finishes**, and `/Android/data/<pkg>` goes with
 * it - taking every artifact the gates just produced. Download outlives the run.
 *
 * The cost is that a reinstall changes the app's uid, so files an earlier run left behind
 * belong to a stranger and cannot be overwritten. [write] deletes first for that reason, and
 * `tools/run-gates.sh` grants `MANAGE_EXTERNAL_STORAGE` after installing so the folder is
 * readable as a whole.
 */
object Gates {

    fun dir(@Suppress("UNUSED_PARAMETER") context: Context): File =
        File(DEVICE_PATH).apply { mkdirs() }

    /**
     * Replace a file, rather than write over it.
     *
     * `File.writeText` on a path owned by a previous install's uid fails with EACCES - which
     * reads as a broken feature and is only stale state.
     */
    fun write(file: File, text: String) {
        file.delete()
        file.writeText(text)
    }

    /** The same directory as `adb shell` sees it. Kept here so the host scripts agree. */
    const val DEVICE_PATH = "/storage/emulated/0/Download/filet-gates"
}
