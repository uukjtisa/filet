package dev.niccc2007.filet.vfs.provider

/**
 * [Stats] backed by `android.system.Os`.
 *
 * ## The two kinds of failure are not the same
 *
 * `Os.stat` failing on one path is routine - a dangling symlink, a child of a folder the app
 * can enter but not fully read. The next path is unaffected, so the answer is null and the walk
 * continues.
 *
 * `Os` being absent is a different thing entirely: it means this is a unit test on a desktop
 * JVM, every subsequent call will fail the same way, and continuing to try would throw and
 * catch once per entry for the whole listing. So that case latches [usable] off and the
 * provider uses its fallback from then on.
 *
 * The two are told apart by class name rather than by a typed `catch`, deliberately. Matching
 * `catch (e: ErrnoException)` would make the runtime load `ErrnoException` to test the throwable
 * against it - on the very platform where the failure being handled is that the class is not
 * there. Comparing a string cannot fail that way.
 */
object OsStats : Stats {

    @Volatile
    private var usable = true

    override fun of(path: String): Stat? {
        if (!usable) return null
        return try {
            val s = android.system.Os.stat(path)
            Stat(
                mode = s.st_mode,
                size = s.st_size,
                mtimeMillis = mtimeOf(s),
                ino = s.st_ino,
                uid = s.st_uid,
                gid = s.st_gid,
            )
        } catch (t: Throwable) {
            if (t.javaClass.name != "android.system.ErrnoException") usable = false
            null
        }
    }

    /**
     * Modification time in milliseconds, at the best precision this OS version offers.
     *
     * `st_mtime` is whole seconds, and `File.lastModified()` is milliseconds - so reading only
     * the seconds field would quietly coarsen every timestamp in the app. That matters in two
     * places: a sort by date would tie rows that used to order, and the index decides a file
     * has changed by comparing its recorded mtime, so a second edit inside the same second
     * would look like no edit at all.
     *
     * `st_mtim` carries nanoseconds and arrived in API 27, one release above this module's
     * floor of 26. Hence the check: full precision everywhere except Android 8.0, where the
     * field does not exist and seconds are all there is.
     */
    private fun mtimeOf(s: android.system.StructStat): Long =
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            StatFacts.mtimeMillis(s.st_mtim.tv_sec, s.st_mtim.tv_nsec)
        } else {
            StatFacts.mtimeMillis(s.st_mtime, 0L)
        }
}
