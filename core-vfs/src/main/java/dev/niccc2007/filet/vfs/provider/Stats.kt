package dev.niccc2007.filet.vfs.provider

/**
 * One filesystem stat, reduced to the fields a directory listing needs.
 *
 * ## Why this type exists at all
 *
 * A listing used to build each row out of `java.io.File` accessors, and every one of those is
 * its own trip into the kernel:
 *
 * ```
 * isDir    = isDirectory      // stat
 * size     = if (isDirectory) // stat, again
 *            -1L else length()// stat
 * mtime    = lastModified()   // stat
 * readable = canRead()        // access
 * writable = canWrite()       // access
 * inode    = Os.lstat(..)     // lstat
 * ```
 *
 * Seven round trips per row for facts that a single `stat` returns together. Measured in the
 * app on a 227-entry folder: reading the directory took 37 ms and building the rows took
 * 626 ms - 2.75 ms per entry, seventeen times the cost of the read it was decorating.
 *
 * ## Why it is that expensive, when the shell says a stat costs 32 us
 *
 * Because it is not the same stat. App access to `/storage/emulated/0` goes through FUSE, so
 * every metadata call is a round trip out to a userspace daemon in another process, not a
 * kernel-side lookup. The shell reads the same folder over a different mount and pays the cheap
 * price, which is exactly why a shell timing said the metadata was free and the app said it was
 * the whole cost. Under FUSE the number of calls IS the cost, and nothing else about the listing
 * comes close.
 *
 * So the fix is arithmetic rather than cleverness: ask once.
 */
data class Stat(
    /** `st_mode`: the file type bits and the permission bits together. */
    val mode: Int,
    val size: Long,
    val mtimeMillis: Long,
    val ino: Long,
    val uid: Int,
    val gid: Int,
)

/**
 * Reads a [Stat] for a path.
 *
 * A seam, so everything that decides something from a stat can be tested on a plain JVM. The
 * real implementation is [Stats.OS] and needs a device; a test hands in a map.
 */
fun interface Stats {

    /**
     * Null when this path cannot be statted, or when stat is not available at all.
     *
     * Both are ordinary answers rather than failures. A broken symlink is genuinely unstattable
     * and a caller can still show its name; a JVM unit test has no `android.system.Os` and the
     * caller falls back to the per-field route it used before.
     */
    fun of(path: String): Stat?

    companion object {
        /** The real thing. Present so callers do not each re-derive the guard below. */
        val OS: Stats = OsStats
    }
}

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
private object OsStats : Stats {

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

/** Whether a row can be read and written. */
data class Perm(val readable: Boolean, val writable: Boolean)

/** The decisions made from a [Stat]. Pure, so each one is pinned by a test. */
object StatFacts {

    /** The file-type mask and the directory type, from `sys/stat.h`. */
    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000

    /**
     * Just the permission bits - the low twelve, so setuid, setgid and sticky are kept.
     *
     * The type bits are deliberately dropped, and that is what makes [PermProbe] worth having:
     * `access(2)` answers from the permission bits and the caller's credentials and does not
     * care whether it is looking at a file or a folder, so a directory at 0755 and a file at
     * 0755 owned by the same pair are the same question. Keeping the type bits in the key would
     * split a camera folder into two probes instead of one, for no difference in the answer.
     */
    private const val PERM_BITS = 0xFFF

    fun isDir(mode: Int): Boolean = (mode and S_IFMT) == S_IFDIR

    fun permBits(mode: Int): Int = mode and PERM_BITS

    fun mtimeMillis(seconds: Long, nanos: Long): Long = seconds * 1000L + nanos / 1_000_000L
}

/**
 * `access(2)` answers, memoised by the attributes that determine them.
 *
 * ## Why this is a cache and not a calculation
 *
 * The obvious move, once a stat is in hand, is to work readable and writable out from the mode
 * bits directly: compare the caller's uid to `st_uid`, its groups to `st_gid`, pick the owner,
 * group or other triplet. That is the POSIX rule and it is free.
 *
 * It is also a guess. Supplementary groups are not reachable from `android.system.Os`, a
 * read-only mount makes every write bit a lie, and a FUSE daemon is free to answer `access`
 * however it likes regardless of the mode it reported. Getting it wrong shows a padlock on a
 * file that is perfectly writable, or hides one that is not - silently, on a row the person is
 * about to act on.
 *
 * So the answer still comes from the kernel. What changes is how often it is asked: permission
 * is a function of the file's mode, owner and group and of the caller, and the caller is fixed
 * for the process - so two entries with the same three attributes have the same answer, and the
 * second one can be read off the first. A camera folder is thousands of files written by one
 * app with identical bits: one signature, two syscalls, instead of two per file.
 *
 * ## What it can still get wrong
 *
 * A per-file SELinux label or a POSIX ACL can make two entries with identical mode, uid and gid
 * differ. That is the one case this reports wrong, it is rare on the storage Filet lists, and
 * the consequence is a display detail rather than a lost write: every actual read and write
 * still goes to the filesystem and still fails properly if it is not allowed.
 */
class PermProbe(private val probe: (String) -> Perm) {

    private data class Sig(val mode: Int, val uid: Int, val gid: Int)

    private val seen = HashMap<Sig, Perm>()

    /** How many real probes were made. The point of the class, so it is observable. */
    var probes: Int = 0
        private set

    fun of(path: String, st: Stat): Perm {
        val sig = Sig(StatFacts.permBits(st.mode), st.uid, st.gid)
        seen[sig]?.let { return it }
        probes++
        val answer = probe(path)
        seen[sig] = answer
        return answer
    }
}
