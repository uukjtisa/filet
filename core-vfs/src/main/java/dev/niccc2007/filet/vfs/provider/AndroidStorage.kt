package dev.niccc2007.filet.vfs.provider

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * The one place that asks Android where storage is.
 *
 * Isolated here so [LocalProvider] stays plain JVM and unit-testable, and so PLAN.md R3 has
 * exactly one file to point at when someone asks where `Environment` is allowed to appear.
 */
object AndroidStorage {

    /**
     * Volumes reachable with direct file access.
     *
     * Without MANAGE_EXTERNAL_STORAGE this still returns the shared volume, but listing
     * much of it will fail with AccessDenied — which the UI surfaces rather than hiding,
     * because a silently empty folder is worse than a stated refusal.
     */
    fun roots(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        out += Environment.getExternalStorageDirectory()
        // getExternalFilesDirs returns app-scoped dirs on every volume; walking up four
        // levels reaches each volume root, which is how SD cards are discovered without
        // the StorageManager reflection dance.
        //
        // The result is then filtered by [looksLikeVolume], because on some devices and on
        // the emulator this list also contains an app-private path under /data - and walking
        // four levels up from that lands on "/data", which is not a volume and shows up as a
        // junk drive on the Home screen.
        // Removable volumes by what the system says is mounted, which covers a USB drive: the
        // per-volume app folders below are only made for volumes the system offers apps.
        Removables.plugged(context).mapNotNull { it.path }.forEach { out += File(it) }
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            var v: File? = appDir
            repeat(4) { v = v?.parentFile }
            v?.takeIf { it.exists() && looksLikeVolume(it) }?.let { out += it }
        }
        return out.filter { it.exists() }
    }

    /**
     * A volume root is `/storage/<something>` - the shared volume or a removable card.
     *
     * `/storage/self` is the symlink to the current user's view and would duplicate the
     * primary volume under a second, confusing name.
     */
    private fun looksLikeVolume(dir: File): Boolean {
        val path = dir.absolutePath
        if (!path.startsWith("/storage/")) return false
        if (path.startsWith("/storage/self")) return false
        return path.trim('/').split('/').size in 2..3
    }

    /**
     * The phone's local provider. Its roots are asked again on every listing of drives, so an SD
     * card or USB drive plugged in after launch appears without a restart, and one pulled out
     * goes. See [Removables].
     */
    fun localProvider(context: Context) =
        LocalProvider(emptyList(), stats = OsStats, liveRoots = { roots(context) })

    /**
     * The content URI that signals "shared storage changed".
     *
     * Lives here rather than in the index scheduler: knowing which URI represents storage IS
     * storage knowledge, and R3 keeps that at L0. The scheduler only needs something to hang
     * a `JobInfo.TriggerContentUri` on.
     */
    val mediaChangeUri: android.net.Uri
        get() = android.provider.MediaStore.Files.getContentUri("external")
}
