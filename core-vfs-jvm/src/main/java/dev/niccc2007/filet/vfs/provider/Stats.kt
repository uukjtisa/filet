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
        /**
         * The reader for a platform that has no `stat` to offer.
         *
         * Always null, which is an answer every caller already handles — [LocalProvider] falls
         * back to the per-field `java.io.File` route, and on a desktop that route is the cheap
         * one anyway: the FUSE round trip that makes a per-field listing expensive on Android
         * is not in the way there.
         *
         * The real Android reader is `OsStats` in `:core-vfs`, and it is passed in rather than
         * reached for. It cannot live here: naming `android.system.Os` is what would stop this
         * module compiling off a device, which is the whole reason it is its own module.
         */
        val NONE: Stats = Stats { null }
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
