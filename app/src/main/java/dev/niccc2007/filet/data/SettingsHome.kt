package dev.niccc2007.filet.data

import android.content.Context
import java.io.File

/**
 * Filet's configuration, kept where losing the app does not lose it.
 *
 * ## Why this exists
 *
 * Android deletes an app's private storage when the app is uninstalled, when its data is
 * cleared, and - the way it actually happened - when a developer tool reinstalls it. All of the
 * configuration went with it: every playlist, every bookmark, every setting, every tracked
 * folder. There was no copy anywhere, and nothing could be recovered.
 *
 * So the working copy stays where it has to be and the AUTHORITY moves somewhere a wipe cannot
 * reach: `Filet/Settings`, beside the logs and the scripts, visible and copyable.
 *
 * ## Why SharedPreferences is still the working store
 *
 * It is read during `Application.onCreate`, before All-files access can be assumed, and a
 * configuration store that cannot be read until a permission is granted is one that fails on
 * every first launch and after every permission reset. It is also atomic, typed, and already
 * holds everything. Replacing it with a file in shared storage to save a copy step would mean
 * writing a database to avoid writing a copy.
 *
 * ## Why the FILES are copied rather than the values re-serialised
 *
 * `SettingsTransfer` exists and builds a tidy, documented export - and it would NOT have saved
 * what was lost. Its sections do not include playlists, tabs or recents; its encoder returns
 * null for string sets. A transfer file is for carrying chosen settings to another phone, which
 * is a different job from not losing anything.
 *
 * A SharedPreferences file is a documented XML format that Android itself reads and writes.
 * Copying it whole captures every key, of every type, in every store - including
 * `playlists.v1`, which is the thing he actually lost - and cannot fall behind a store somebody
 * adds later. The stores are ENUMERATED from disk for the same reason: a hard-coded list is a
 * list to forget to add to, which is the failure `check-activities` was written for.
 *
 * ## The one store that is not copied
 *
 * `filet-net` holds remote connections, encrypted with AES-GCM under a key in the Android
 * keystore - and that key is destroyed when the app is uninstalled. A raw copy of that file
 * would restore ciphertext that nothing can ever decrypt: present, plausible, and permanently
 * unreadable, which is worse than it being absent because it looks like it worked.
 *
 * Connections travel through [NET_STORE]'s own export instead, without secrets, and are
 * re-encrypted under the new key on the way back in. Passwords are deliberately not carried:
 * shared storage is readable by any app holding storage permission, and a convenience that
 * writes somebody's SMB password where anything can read it is a worse outcome than retyping
 * it.
 */
object SettingsHome {

    /** Where Android keeps SharedPreferences. Not an API, but a stable one since forever. */
    private const val PREFS_DIR = "shared_prefs"

    /**
     * The connections store, excluded from the raw copy.
     *
     * See the class note: its contents are encrypted under a keystore key that does not survive
     * an uninstall, so copying the file preserves bytes and destroys meaning.
     */
    const val NET_STORE = "filet-net"

    /**
     * The connections carried alongside the raw stores, as their own export.
     *
     * The file [NET_STORE] is excluded because its key dies with the app; this is the same
     * connections, re-exported WITHOUT secrets so they can be read back and re-encrypted under
     * whatever key the next install gets.
     *
     * Excluding the store and stopping there was the first cut, and it was wrong: it threw
     * away the hosts, ports, shares and usernames along with the passwords, so a wipe meant
     * rebuilding every remote by hand. Only the passwords genuinely cannot travel - everything
     * else about a connection is ordinary configuration.
     */
    const val NET_EXPORT = "connections.json"

    /** How many snapshots to keep. */
    const val KEEP = 5

    private fun prefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, PREFS_DIR)

    private fun home(context: Context): File =
        FiletStore.dir(context, FiletStore.Area.SETTINGS)

    /**
     * Every preference store the app actually has, found by looking.
     *
     * Enumerated rather than listed. There are about a dozen - the main one, playback resume,
     * the beacon, the report form, the widgets - and a hard-coded list would silently miss the
     * thirteenth. What is missed is not noticed until the day it is needed.
     */
    fun stores(context: Context): List<File> = runCatching {
        prefsDir(context).listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }
            .orEmpty()
            .filter { it.nameWithoutExtension != NET_STORE }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** Whether there is anything worth restoring from. */
    fun hasSnapshot(context: Context): Boolean = newest(context) != null

    /**
     * When the newest snapshot was written, or null when there is none.
     *
     * A number rather than the folder, because the caller is a settings screen and R3 keeps
     * storage handles out of every layer but this one - a `File` in a composable's signature
     * counts even when nothing is opened with it. The screen needs to know whether a snapshot
     * exists and how old it is; the folder itself is this object's business.
     */
    fun newestAt(context: Context): Long? =
        newest(context)?.name?.removePrefix(STAMP_PREFIX)?.toLongOrNull()

    /** The most recent snapshot folder, or null. */
    fun newest(context: Context): File? = runCatching {
        home(context).listFiles { f: File -> f.isDirectory && f.name.startsWith(STAMP_PREFIX) }
            .orEmpty()
            .filter { it.listFiles().orEmpty().isNotEmpty() }
            .maxByOrNull { it.name }
    }.getOrNull()

    /**
     * Write a snapshot, unless it would be a worse one than the newest already there.
     *
     * @return the folder written, or null when nothing was written.
     */
    fun snapshot(context: Context, connections: String? = null): File? = runCatching {
        val sources = stores(context)
        if (sources.isEmpty()) return null
        val weight = weigh(sources.sumOf { it.length() }, sources.size)
        // THE GUARD THAT MATTERS.
        //
        // Sequence this prevents: the app's data is wiped, it starts with nothing, a change
        // fires the writer, and a snapshot of an empty app replaces the only good copy -
        // before anybody has been asked whether to restore. The safety net would then delete
        // the thing it exists to protect, silently, seconds after the event it was built for.
        //
        // So a snapshot that is materially smaller than the newest one is not written at all.
        // Growth is always allowed; shrinkage has to be gradual or it is not somebody editing
        // their settings, it is their settings being gone.
        val previous = newest(context)
        if (previous != null && shrunk(weight, weighFolder(previous))) return null

        val into = File(home(context), STAMP_PREFIX + System.currentTimeMillis())
        if (!into.mkdirs() && !into.isDirectory) return null
        for (f in sources) {
            runCatching { f.copyTo(File(into, f.name), overwrite = true) }
        }
        // The connections, if the caller handed them over. Written as their own export rather
        // than as the encrypted store - see NET_EXPORT.
        connections?.let { json ->
            runCatching { File(into, NET_EXPORT).writeText(json) }
        }
        prune(context)
        into
    }.getOrNull()

    /**
     * The connections export from the newest snapshot, or null.
     *
     * Read separately from [restore] because it is applied at a different moment. The raw
     * stores go back before anything has read them; connections cannot, because decoding them
     * needs the connection store itself, which belongs to the object graph. So they are put
     * back just after the graph exists, and only when there are none.
     */
    fun pendingConnections(context: Context): String? = runCatching {
        val file = File(newest(context) ?: return null, NET_EXPORT)
        if (!file.isFile) return null
        file.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Copy a snapshot back over the app's preference stores.
     *
     * **Only safe before anything has read them.** The `Prefs` StateFlows are built from the
     * stored values when the object is constructed, so values written underneath a running app
     * are not seen until the process restarts. Callers either do this before the object graph
     * exists, or restart afterwards - there is no third option, and a restore that appears to
     * do nothing is the worst of the three.
     *
     * @return how many stores were written.
     */
    fun restore(context: Context, from: File? = null): Int = runCatching {
        val source = from ?: newest(context) ?: return 0
        val files = source.listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }.orEmpty()
        if (files.isEmpty()) return 0
        val dir = prefsDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return 0
        var done = 0
        for (f in files) {
            // Never the connections store, even if an older snapshot carried one: its key is
            // gone and the bytes are undecryptable. See the class note.
            if (f.nameWithoutExtension == NET_STORE) continue
            if (runCatching { f.copyTo(File(dir, f.name), overwrite = true) }.isSuccess) done++
        }
        done
    }.getOrDefault(0)

    // ── the decisions, kept pure so they can be tested ────────────────────────────────────

    const val STAMP_PREFIX = "settings-"

    /**
     * How much a snapshot is "worth", as bytes and store count together.
     *
     * Two numbers rather than one because they fail differently: a wiped app has FEWER stores,
     * and an emptied playlist has the same store at a smaller size. Either alone would miss
     * half the cases this is guarding against.
     */
    fun weigh(bytes: Long, stores: Int): Pair<Long, Int> = bytes to stores

    /** A folder's weight, for comparing against a candidate. */
    fun weighFolder(dir: File): Pair<Long, Int> {
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }.orEmpty()
        return weigh(files.sumOf { it.length() }, files.size)
    }

    /**
     * Whether [candidate] is so much smaller than [previous] that it looks like loss.
     *
     * Not any shrinkage - somebody deleting a playlist legitimately shrinks the file, and a
     * store that could never shrink would stop tracking reality. Losing a whole STORE, or
     * more than [SHRINK_FLOOR] of the bytes, is the shape of a wipe rather than of an edit.
     */
    fun shrunk(candidate: Pair<Long, Int>, previous: Pair<Long, Int>): Boolean {
        if (previous.second > 0 && candidate.second < previous.second) return true
        if (previous.first <= 0L) return false
        return candidate.first < previous.first * SHRINK_FLOOR
    }

    /** Below this share of the previous size, a snapshot is treated as loss rather than editing. */
    const val SHRINK_FLOOR = 0.5

    private fun prune(context: Context) {
        runCatching {
            home(context).listFiles { f: File -> f.isDirectory && f.name.startsWith(STAMP_PREFIX) }
                .orEmpty()
                .sortedByDescending { it.name }
                .drop(KEEP)
                .forEach { it.deleteRecursively() }
        }
    }
}
