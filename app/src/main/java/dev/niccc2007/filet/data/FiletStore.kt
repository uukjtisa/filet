package dev.niccc2007.filet.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Everything Filet keeps on disk, in one folder somebody can open, read and delete.
 *
 * ## The fault
 *
 * The app's working files were scattered across four places nobody could reach: beat maps and
 * decoded audio in `cacheDir`, crash reports and scripts in `filesDir`, updates in
 * `getExternalFilesDir`, logs alone in `/storage/emulated/0/Filet/Logs`. Two consequences, and
 * the first is the serious one:
 *
 * **`cacheDir` is not storage, it is a suggestion.** Android empties it whenever the device
 * wants the space, without asking and without telling the app. A beat map measured over two
 * seconds of spectrum analysis was therefore kept only "until something else needed the room",
 * so a track measured on Monday was measured again on Friday and the work the pulse depends on
 * silently evaporated.
 *
 * **None of it was reachable.** A cache nobody can see is a cache nobody can clear, inspect or
 * copy off the phone - in a FILE MANAGER, which is the one app where that is indefensible.
 *
 * ## The shape
 *
 * ```
 * Filet/
 *   Logs/            what the app did
 *   Scripts/         your work
 *   Reports/         crashes and diagnostics
 *   .cache/          everything derived - safe to delete, costs only time to rebuild
 *     Beat maps/
 *     Decoded audio/
 *     ...
 * ```
 *
 * The split is the point. Everything under `.cache` can be deleted without losing anything that
 * cannot be made again; nothing outside it can. Somebody reclaiming space needs to know which
 * is which before they delete, not after, and [Area.disposable] is that answer in one place.
 *
 * The dot is deliberate too: it keeps ten megabytes of decoded samples out of every gallery and
 * music app's media scanner. Filet shows hidden entries, so it stays reachable from the app
 * that owns it.
 *
 * ## The trade this makes, and the way out of it
 *
 * A cache Android manages is emptied for you when the phone runs short. This one is not - that
 * is the whole point, and it is also a real cost, because a cache that only ever grows is a
 * cache that will eventually be in somebody's way. Two things answer it: the home screen says
 * how big it has become once it is worth saying so, and [Location] lets anybody who would
 * rather have the old behaviour have it back.
 *
 * ## What stays put
 *
 * The index database. It is SQLite's own file, it is rebuilt from the filesystem anyway, and
 * putting a database somebody can delete half of in the middle of a write is a corruption
 * report waiting to happen.
 *
 * ## When there is no permission
 *
 * Shared storage needs All-files access, which a file manager asks for but may not have been
 * given yet. Without it everything falls back to app-private external storage, which still
 * works and is still deletable, just less reachable. Nothing here throws: a store that fails is
 * worse than a store that quietly uses somewhere else.
 */
object FiletStore {

    /** The folder, under the root of shared storage. Also the name the logs already used. */
    const val ROOT = "Filet"

    /**
     * The derived half, hidden from other apps' media scanners.
     *
     * Dotted rather than carrying a `.nomedia`, because a dot hides the folder from a file
     * listing too - somebody browsing `Filet/` should see the three folders that hold their
     * things, not ten megabytes of decoded samples sitting at the same level.
     */
    const val CACHE_FOLDER = ".cache"

    /**
     * Where the derived files go.
     *
     * Named for what somebody experiences rather than for the API underneath. "Internal" and
     * "external cache directory" are Android's words for where the bytes land; what actually
     * differs is **who clears it and whether you can see it**. "Filet folder" is a folder
     * somebody can open; "Internal app storage" is Android's own wording for the part it clears
     * and deletes with the app, so the label carries that without a note under it.
     */
    enum class Location(val label: String) {
        /** The default. Visible, permanent, yours to clear. */
        FILET("Filet folder"),

        /** What the app did before. Invisible, and emptied without warning. */
        ANDROID("Internal app storage"),
        ;

        companion object {
            val DEFAULT = FILET

            fun of(id: String?): Location = entries.firstOrNull { it.name == id } ?: DEFAULT
        }
    }

    /**
     * One kind of thing Filet keeps.
     *
     * @param folder the directory name. Spelled for a person reading a file list rather than
     *   for a programmer - this is a folder somebody opens.
     * @param from where this used to live, so [migrate] can move it once.
     */
    enum class Area(val folder: String, val from: Legacy?) {

        // ── kept: losing these loses something ───────────────────────────────────────────

        LOGS("Logs", null),
        SCRIPTS("Scripts", Legacy.FILES_SCRIPTS),
        REPORTS("Reports", Legacy.FILES_CRASH),

        /**
         * What Filet itself remembers, as opposed to what it worked out.
         *
         * The distinction this whole enum exists for, and the one case that nearly ended up on
         * the wrong side of it. Everything under `.cache` can be deleted because walking the
         * filesystem recreates it. This cannot: "when did this phone first see this file" is
         * not written down anywhere else - not in the file, not by Android, not by the
         * filesystem. The nearest-looking substitutes are traps. A photo's EXIF capture date
         * and MediaStore's DATE_TAKEN prove the CONTENT existed by then, which is a different
         * claim: a picture taken in 2019 and sent over a messenger yesterday would read as
         * having been on this phone since 2019. Modification time fails the same way for a
         * file copied off a desktop with its timestamp preserved.
         *
         * So it is not disposable, and being non-disposable earns it four things from the code
         * above with nothing extra written: the cache-location setting does not move it,
         * neither Clear button offers it, and the home screen never counts it or invites
         * anybody to delete it. It is still a visible folder somebody could delete on purpose,
         * which is true of every file on the phone and is not the same as the app inviting it.
         */
        MEMORY("Memory", null),

        /**
         * Filet's own configuration, where an uninstall cannot reach it.
         *
         * Settings, playlists, bookmarks, tracked folders - everything that was lost when a
         * developer tool reinstalled the app and took its private storage with it. The working
         * copy is still SharedPreferences, because that is readable before any permission is
         * granted; this is the authority it is restored from. See [SettingsHome].
         *
         * Not disposable, and NOT moved by the storage setting. A control worded about working
         * files that could quietly put somebody's playlists back where a wipe deletes them is a
         * trap, and the cost of that trap is already known.
         */
        SETTINGS("Settings", null),

        // ── derived: deleting these costs only the time to make them again ───────────────

        /**
         * Measured beats, one small file per track.
         *
         * The reason this class exists. Seventy kilobytes for three minutes of music and two
         * seconds of CPU to produce - worth keeping for ever, and being thrown away by the
         * system within days.
         */
        BEATS("Beat maps", Legacy.CACHE_AUDIO),

        /** Decoded samples. Ten megabytes a track, capped and evicted by whoever writes them. */
        AUDIO("Decoded audio", Legacy.CACHE_AUDIO),

        /** Local copies of remote files, for the APIs that cannot read a stream. */
        REMOTE("Remote files", Legacy.CACHE_REMOTE),

        /** Local copies of anything a document reader had to be handed as a real file. */
        COPIES("Opened copies", Legacy.CACHE_COPIES),

        /** A track staged for a player that seeks and will not take a stream. */
        PLAYBACK("Playback", Legacy.CACHE_PLAY),

        /** Files other apps shared INTO Filet, before they are filed. */
        RECEIVED("Received", Legacy.CACHE_INBOX),

        /** A split bundle taken apart to be read. */
        BUNDLES("App bundles", Legacy.CACHE_BUNDLE),

        /** Screenshots in the release notes. */
        NOTES("Release notes", Legacy.CACHE_NOTES),

        /**
         * The search index.
         *
         * Derived - it is rebuilt by walking the filesystem - so it belongs with the rest of
         * the derived half and moves with the setting like everything else.
         *
         * `from` is null on purpose: this one is NOT migrated. The database is three files in
         * WAL mode and the move would have to happen before anything opens it, while the
         * migration runs off the main thread and the index is opened by the object graph -
         * a race whose losing outcome is a half-copied database rather than a cache miss. It
         * costs a re-crawl once, and the stale copy is deleted by `IndexDb` at the moment it
         * opens the new one, which is the only point where nothing can be holding it.
         *
         * Note that shared storage is FUSE-backed, so this is slower for random writes than
         * app-private storage was. That is the price of the folder being reachable, and it is
         * the setting's whole purpose to let somebody decline it.
         */
        INDEX("Search index", null),

        /**
         * A downloaded update, waiting to be installed.
         *
         * Was `getExternalFilesDir("update")`, which is app-private and invisible - so an
         * update that downloaded and then failed to install left a file somebody could neither
         * find nor delete, and no way to confirm the download had even happened.
         */
        UPDATES("Updates", Legacy.FILES_UPDATE),

        /** A decompiled APK's working copy, named after the APK it came from. */
        APKWORK("APK workspace", Legacy.FILES_APKWORK),

        /**
         * Pictures a document showed - a README's badges and screenshots - so reopening it does
         * not fetch them again. Capped by the image loader that writes it; see `DocPictures`.
         */
        PICTURES("Document pictures", null),
        ;

        /**
         * Whether deleting it loses anything that cannot be made again.
         *
         * This is also what decides whether it lives under [CACHE_FOLDER] and whether
         * [Location] moves it, so there is ONE answer rather than three lists to keep in step.
         * Exhaustive, so a tenth area cannot be added without deciding it.
         */
        val disposable: Boolean
            get() = when (this) {
                BEATS, AUDIO, REMOTE, COPIES, PLAYBACK, RECEIVED, BUNDLES, NOTES, INDEX,
                UPDATES, APKWORK, PICTURES,
                -> true
                // The app's own record, somebody's work, and the reports worth getting off the
                // phone. None of these can be made again.
                LOGS, SCRIPTS, REPORTS, MEMORY, SETTINGS -> false
            }

        /** What this folder holds, for the screen that lists them. */
        val note: String
            get() = when (this) {
                LOGS -> "One log file per run"
                SCRIPTS -> "Saved scripts"
                REPORTS -> "Crash reports and diagnostics"
                MEMORY -> "When each file first arrived; this cannot be rebuilt"
                SETTINGS -> "Settings, playlists and bookmarks, kept after uninstall"
                BEATS -> "Measured beats for each track"
                AUDIO -> "Decoded audio for faster replays"
                REMOTE -> "Copies of files opened from a share"
                COPIES -> "Copies made to open documents"
                PLAYBACK -> "Tracks staged for the player"
                RECEIVED -> "Files other apps sent to Filet"
                BUNDLES -> "Unpacked split APKs"
                NOTES -> "Pictures in the release notes"
                INDEX -> "The search index; rebuilt if deleted"
                UPDATES -> "Downloaded updates waiting to install"
                APKWORK -> "Unpacked APKs being inspected"
                PICTURES -> "Pictures shown in documents"
            }
    }

    /** Where an area's files used to live, for the one-time move. */
    enum class Legacy {
        CACHE_AUDIO, CACHE_REMOTE, CACHE_COPIES, CACHE_PLAY, CACHE_INBOX, CACHE_BUNDLE,
        CACHE_NOTES, CACHE_REPORT, FILES_CRASH, FILES_SCRIPTS, FILES_UPDATE, FILES_APKWORK,
    }

    // ── where things are ──────────────────────────────────────────────────────────────────

    /**
     * Whether shared storage can actually be written.
     *
     * Checked rather than assumed: the app asks for All-files access and may not have it yet,
     * and on a device where it is refused everything here still has to work.
     */
    fun reachable(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return false
        }
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }.getOrDefault(false)

    /** The `Filet` folder, shared when it can be and app-private when it cannot. */
    fun root(context: Context): File {
        if (reachable()) {
            val shared = File(Environment.getExternalStorageDirectory(), ROOT)
            if (runCatching { shared.isDirectory || shared.mkdirs() }.getOrDefault(false)) return shared
        }
        return File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT)
            .apply { runCatching { mkdirs() } }
    }

    /**
     * The setting, read from storage rather than passed down.
     *
     * Every caller wants "wherever the cache is", and threading that through nine call sites -
     * several of them deep inside a decoder - would mean nine chances to pass the wrong one, or
     * to pass a default and silently write to the folder the setting says not to use. Reading
     * one string out of SharedPreferences is cheaper than the file operation about to follow
     * it, and `Prefs` writes the same file under the same key.
     */
    fun current(context: Context): Location = Location.of(
        runCatching {
            context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)
                .getString(PrefKeys.CACHE_LOCATION, null)
        }.getOrNull(),
    )

    /** The `Filet` folder as a plain path, for the callers that must not hold a File. */
    fun rootPath(context: Context): String = root(context).absolutePath

    /** The derived half. What the home screen's reminder offers to open. */
    fun cacheRoot(context: Context, where: Location = current(context)): File =
        if (where == Location.ANDROID) context.cacheDir
        else File(root(context), CACHE_FOLDER).apply { runCatching { mkdirs() } }

    /**
     * One area's folder, created on demand.
     *
     * [Location] moves only the derived areas. The logs, the scripts and the reports are not a
     * cache and are not somewhere Android may empty, whichever way the setting is set - that is
     * the difference [Area.disposable] names.
     */
    fun dir(context: Context, area: Area, where: Location = current(context)): File {
        val parent = if (area.disposable) cacheRoot(context, where) else root(context)
        return File(parent, area.folder).apply { runCatching { mkdirs() } }
    }

    // ── size and clearing ─────────────────────────────────────────────────────────────────

    /**
     * An area's folder as a plain path.
     *
     * For the callers that must not hold a `java.io.File` - R3 keeps storage handles out of
     * every layer but this one, and a `File` in a constructor SIGNATURE counts even when the
     * body never touches it. `core-index` takes its database location this way.
     */
    fun dirPath(context: Context, area: Area, where: Location = current(context)): String =
        dir(context, area, where).absolutePath

    /** What a folder occupies, in bytes. Walks it; call it off the main thread. */
    fun sizeOf(dir: File): Long = runCatching {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun sizeOf(context: Context, area: Area, where: Location = current(context)): Long =
        sizeOf(dir(context, area, where))

    /** Everything derived, across both possible homes, so a changed setting is still counted. */
    fun cacheSize(context: Context): Long =
        Location.entries.sumOf { sizeOf(cacheRoot(context, it)) }

    /** Empty a folder, leaving the folder itself. */
    fun clear(dir: File): Boolean = runCatching {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        true
    }.getOrDefault(false)

    fun clear(context: Context, area: Area, where: Location = current(context)): Boolean =
        clear(dir(context, area, where))

    // ── the one-time move ─────────────────────────────────────────────────────────────────

    /**
     * Move what the old locations hold into the new ones, once.
     *
     * ## Why a move and not a fresh start
     *
     * Somebody already using the app has beat maps for their library measured. A new folder
     * with a note saying the old one is obsolete would mean re-measuring all of it for no
     * reason other than that the code changed, which is a cost the person did not incur.
     *
     * ## Why it is safe to run on every start
     *
     * The flag that gates it is an optimisation, not the correctness argument: the work is
     * idempotent. It moves files that exist into a folder, skipping any name already taken, and
     * a second run finds nothing to move. A flag that is lost - or a downgrade that never set
     * it - must not corrupt anything.
     *
     * Best-effort throughout. A file that will not move is left where it is and the rest
     * continue: a half-finished migration is a cache miss, and a migration that throws on start
     * is an app that will not open.
     *
     * @return how many files were moved, for the log.
     */
    fun migrate(context: Context): Int {
        var moved = 0
        for (area in Area.entries) {
            val from = legacyDir(context, area.from ?: continue) ?: continue
            if (!from.isDirectory) continue
            val into = dir(context, area)
            if (runCatching { from.canonicalPath == into.canonicalPath }.getOrDefault(false)) continue
            for (f in runCatching { from.listFiles() }.getOrNull().orEmpty()) {
                if (!f.isFile || !belongs(area, f.name)) continue
                val target = File(into, f.name)
                if (target.exists()) {
                    runCatching { f.delete() }
                    continue
                }
                val ok = runCatching { f.renameTo(target) }.getOrDefault(false) ||
                    runCatching { f.copyTo(target, overwrite = false); f.delete() }.getOrDefault(false)
                if (ok) moved++
            }
        }
        return moved
    }

    /**
     * Whether a legacy filename belongs to [area].
     *
     * Only the two audio areas have to decide anything: `AudioCache` wrote beat maps and
     * decoded samples into ONE folder, told apart solely by extension. Pure, and tested,
     * because getting it wrong files ten-megabyte sample dumps under "Beat maps" - which reads
     * as the beat maps having become enormous, and would be deleted by somebody clearing what
     * they thought was a small folder.
     *
     * Half-written entries belong to neither. Both writers go to `.part` and rename, so a
     * process killed mid-write leaves one behind; carrying it across would put a truncated file
     * in the new folder, where it would parse as far as its header and be wrong.
     */
    fun belongs(area: Area, fileName: String): Boolean = when (area) {
        Area.BEATS -> fileName.endsWith(BEAT_EXT)
        Area.AUDIO -> fileName.endsWith(PCM_EXT)
        else -> !fileName.endsWith(PART_EXT)
    }

    const val BEAT_EXT = ".beat"
    const val PCM_EXT = ".pcm"
    const val PART_EXT = ".part"

    // ── when to mention it ────────────────────────────────────────────────────────────────

    private const val MB = 1024L * 1024
    private const val GB = 1024L * MB

    /**
     * How big the derived half may get before the home screen says anything - the choices in
     * Settings, smallest first, and 0 for never.
     *
     * Five hundred megabytes by default: the point where it is worth a person's attention on a
     * phone, below which nobody would thank you for the reminder. The rest are for a phone with
     * room to spare, and Never for somebody who looks after the folder themselves.
     */
    val REMIND_STEPS: List<Long> = listOf(500L * MB, 1L * GB, 2L * GB, 5L * GB, 0L)

    const val REMIND_DEFAULT = 500L * MB

    /** A step as Settings names it. */
    fun remindLabel(bytes: Long): String = when {
        bytes <= 0L -> "Never"
        bytes >= GB -> "${bytes / GB} GB"
        else -> "${bytes / MB} MB"
    }


    /**
     * Whether to show the home screen's reminder.
     *
     * The cost of the default having moved out of Android's cache directory: nothing empties
     * this for us any more, so a cache that only ever grows would eventually be in somebody's
     * way with no sign of where it came from. The reminder is the thing that makes "we keep it
     * until you say otherwise" honest rather than sneaky.
     *
     * Never shown while Android is managing it - the setting means the system empties it, so a
     * reminder to do it yourself would be advice to do something already being done.
     *
     * Pure and tested: a reminder that fires at the wrong size is either nagging or useless,
     * and neither shows up until it is in front of somebody.
     */
    // ── the folder explains itself ────────────────────────────────────────────────────────

    /** The note written into the folder's root. */
    const val NOTE_FILE = "About this folder.txt"

    /**
     * Bumped when [folderNote] changes, so an old copy is replaced rather than left to rot.
     *
     * In the text itself rather than beside it: a version kept in a preference would be wiped
     * by the very event this whole file exists to survive, and the note would then never be
     * refreshed again on a phone that had been cleared.
     */
    const val NOTE_VERSION = 2

    /**
     * What the Filet folder is, written into the Filet folder.
     *
     * ## Why a file and not a dialogue
     *
     * The intent was a reminder at uninstall time - the way a game warns that its worlds are
     * about to go. Android does not allow it: an app cannot add anything to the system's
     * uninstall dialogue, and `ACTION_PACKAGE_FULLY_REMOVED` is never delivered to the package
     * being removed. A warning shown *before* somebody decides to uninstall is a warning shown
     * at the wrong time, and the storage screen already says it for anybody who looks.
     *
     * So the note goes where the question actually gets asked. The moment somebody wonders
     * what this folder is, is the moment they find it with Filet already gone - months later,
     * in a file manager, looking at a folder they did not create and cannot identify. A file
     * sitting in it answers that, which is the one version of the reminder that still works
     * when there is no app left to show a screen.
     *
     * Built from [Area.entries] rather than written out, so a tenth area cannot be added
     * without appearing here. A note that silently stops describing the folder is worse than
     * none, because it is read as complete.
     */
    fun folderNote(): String = buildString {
        appendLine("About this folder")
        appendLine("=================")
        appendLine()
        appendLine("Filet made this folder. It holds Filet's own working files, and it is")
        appendLine("deliberately out here where you can see it rather than hidden inside the")
        appendLine("app's private storage.")
        appendLine()
        appendLine("THIS FOLDER SURVIVES UNINSTALLING FILET.")
        appendLine()
        appendLine("It also survives clearing the app's data. That is the point: settings,")
        appendLine("playlists and bookmarks used to live in private storage, where an uninstall")
        appendLine("or a reinstall destroyed them with no copy anywhere. Now they are here.")
        appendLine()
        appendLine("If you reinstall Filet, it reads this folder on first launch and puts your")
        appendLine("settings and playlists back on its own. Saved server passwords are the one")
        appendLine("exception - they are never written here, because anything with storage")
        appendLine("permission could read them. You will be asked for those again.")
        appendLine()
        appendLine("If you do not want this folder, delete it. Nothing breaks: Filet makes")
        appendLine("whatever it needs again, minus the things below marked as kept.")
        appendLine()
        appendLine("What is in it")
        appendLine("-------------")
        appendLine()
        appendLine("KEPT - deleting these loses something that cannot be made again:")
        for (a in Area.entries) {
            if (!a.disposable) appendLine("  " + a.folder + " - " + a.note)
        }
        appendLine()
        appendLine("SAFE TO DELETE - Filet works these out again when it needs them:")
        for (a in Area.entries) {
            if (a.disposable) appendLine("  " + a.folder + " - " + a.note)
        }
        appendLine()
        appendLine(
            "The disposable ones sit under " + CACHE_FOLDER +
                ", which is dotted so a file manager hides",
        )
        appendLine("it by default. Filet's settings screen can show you their sizes and clear")
        appendLine("them one at a time.")
        appendLine()
        appendLine("-- note v" + NOTE_VERSION)
    }

    /**
     * Write [folderNote] into the folder root, when it is absent or out of date.
     *
     * @return true when something was written.
     */
    fun describeFolder(context: Context): Boolean = runCatching {
        if (!reachable()) return false
        val into = File(root(context), NOTE_FILE)
        val wanted = folderNote()
        // Compared rather than overwritten every launch, so this is one read on an ordinary
        // start instead of a write - and so the file's timestamp means "when this changed".
        if (into.isFile && runCatching { into.readText() == wanted }.getOrDefault(false)) {
            return false
        }
        into.writeText(wanted)
        true
    }.getOrDefault(false)

    /**
     * Whether Home mentions [bytes] of derived files, past a [threshold] of [REMIND_STEPS].
     *
     * A dismissal remembers the size it was dismissed at, [dismissedAt], and stays quiet until the
     * folder has grown by one more [threshold] past it: dismissing at 620 MB with the 500 MB step
     * comes back at 1.1 GB, not at the next launch. Zero for never dismissed. Clearing the cache
     * forgets the dismissal, since the size it measured is gone.
     *
     * The caller decides whether there is anything to remind about at all - with the cache in
     * app storage, Android empties it and a reminder would be advice already being followed.
     */
    fun shouldRemind(bytes: Long, threshold: Long, dismissedAt: Long): Boolean {
        if (threshold <= 0L || bytes < threshold) return false
        return dismissedAt <= 0L || bytes >= dismissedAt + threshold
    }

    private fun legacyDir(context: Context, from: Legacy): File? = runCatching {
        when (from) {
            Legacy.CACHE_AUDIO -> File(context.cacheDir, "audio")
            Legacy.CACHE_REMOTE -> File(context.cacheDir, "remote")
            Legacy.CACHE_COPIES -> File(context.cacheDir, "docs")
            Legacy.CACHE_PLAY -> File(context.cacheDir, "play")
            Legacy.CACHE_INBOX -> File(context.cacheDir, "inbox")
            Legacy.CACHE_BUNDLE -> File(context.cacheDir, "bundle-base")
            Legacy.CACHE_NOTES -> File(context.cacheDir, "release-notes")
            Legacy.FILES_CRASH -> File(context.filesDir, "crash")
            Legacy.FILES_SCRIPTS -> File(context.filesDir, "scripts")
            Legacy.CACHE_REPORT -> File(context.cacheDir, "report")
            Legacy.FILES_UPDATE -> context.getExternalFilesDir("update")
            Legacy.FILES_APKWORK -> File(context.getExternalFilesDir(null) ?: context.filesDir, "apk")
        }
    }.getOrNull()
}
