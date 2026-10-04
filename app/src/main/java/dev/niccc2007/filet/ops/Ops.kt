package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.ActionGate
import dev.niccc2007.filet.vfs.Denial
import dev.niccc2007.filet.vfs.FileAction
import dev.niccc2007.filet.vfs.Progress
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.VfsException
import dev.niccc2007.filet.vfs.provider.ArchiveFormat
import dev.niccc2007.filet.vfs.provider.ArchiveOptions
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the clipboard holds and what pasting it should do. */
enum class PendingOp { COPY, MOVE }

/** What to do when a destination name is already taken. */
enum class Conflict { RENAME, OVERWRITE, SKIP }

data class Clipboard(val items: List<VPath>, val op: PendingOp) {
    val size: Int get() = items.size
}

/**
 * The outcome of a batch: partial success is the common case and must be reportable.
 *
 * [denials] are the failures that were REFUSALS rather than faults - a read-only share, an
 * archive, a host that said no. They are also listed in [failed], so a caller that only knows
 * how to report failures still reports them; a caller that can show a dialogue shows one.
 * Separating them is what lets a refusal read as an answer instead of an error.
 */
data class OpResult(
    val succeeded: Int,
    val failed: List<Pair<VPath, String>>,
    val denials: List<Denial> = emptyList(),
) {
    val ok: Boolean get() = failed.isEmpty()

    /** The refusal to put in front of somebody. One dialogue, not one per file. */
    val denial: Denial? get() = denials.firstOrNull()
}

/**
 * The long-running file operations, with progress and honest partial results.
 *
 * Separate from the ViewModel because these are the parts that must keep working when the
 * screen is gone, and because a copy that reports "done" after failing on file 300 of 900 is
 * the single most damaging bug a file manager can have. Every batch returns what actually
 * succeeded and what did not.
 */
class FileOperations(
    private val vfs: Vfs,
    private val ledger: JobLedger,
    /**
     * Told about every path this class writes or removes.
     *
     * A callback rather than a Context, because what has to happen is an Android media-index
     * call and this class is the part that must keep working with no screen attached. The app
     * supplies [dev.niccc2007.filet.media.MediaAnnounce]; a test supplies nothing.
     *
     * Bug identified: a file Filet wrote was on the disk and absent from the gallery, because
     * the gallery reads MediaStore and MediaStore only learns of a file when an app announces
     * it. That is true of everything written here, and of everything deleted here too - an
     * unannounced delete leaves the gallery showing a thumbnail for a file that is gone.
     */
    private val announce: (List<VPath>) -> Unit = {},
) {

    /**
     * Whether [action] may be attempted on [path], asked before anything is touched.
     *
     * Costs nothing - it reads what the backend declares - and catches every refusal that is
     * knowable without a round trip: an archive, an APK, a volume with no write access. What it
     * cannot catch is a host that accepts the connection and refuses the individual write, which
     * is why [ActionGate.fromFailure] exists and why both produce the same [Denial].
     */
    private fun permit(action: FileAction, path: VPath): Denial? = vfs.permit(action, path)

    /**
     * @param onConflict what to do when the destination name is taken. Refusing a whole paste
     *   because one of forty files collides is the behaviour users hate; overwriting silently
     *   is the one that loses data. [Conflict.RENAME] is the default for that reason.
     */
    suspend fun copy(items: List<VPath>, into: VPath, onConflict: Conflict = Conflict.RENAME): OpResult {
        permit(FileAction.CREATE_FILE, into)?.let { return refused(items, it) }
        return batch(items, "Copying", into, FileAction.CREATE_FILE) { src, prog ->
            vfs.copy(src, into, resolveName(src, into, onConflict), prog).path
        }
    }

    /**
     * A move needs BOTH ends: somewhere to put it, and permission to take it away.
     *
     * Checking only the destination is how a move across a boundary becomes a copy that reports
     * success and leaves the original behind - or worse, is attempted and half done. The source
     * check is per item because a selection can span volumes.
     */
    suspend fun move(items: List<VPath>, into: VPath, onConflict: Conflict = Conflict.RENAME): OpResult {
        permit(FileAction.CREATE_FILE, into)?.let { return refused(items, it) }
        items.firstNotNullOfOrNull { permit(FileAction.DELETE, it) }?.let { return refused(items, it) }
        return batch(items, "Moving", into, FileAction.CREATE_FILE) { src, prog ->
            vfs.move(src, into, resolveName(src, into, onConflict), prog).path
        }
    }

    /**
     * Copy each of [items] into the folder it already lives in.
     *
     * The name is worked out per item against what is in that folder AT THAT MOMENT, and the
     * result is added to the set as it goes - so duplicating three files in one gesture cannot
     * hand two of them the same name, which listing once up front would.
     */
    suspend fun duplicate(items: List<VPath>): OpResult {
        val parent = items.firstOrNull()?.parent ?: return OpResult(0, emptyList())
        permit(FileAction.CREATE_FILE, parent)?.let { return refused(items, it) }

        val id = ledger.start("Copying ${items.size} item(s)", parent.name)
        val failed = ArrayList<Pair<VPath, String>>()
        val denials = ArrayList<Denial>()
        val made = ArrayList<VPath>()
        val taken = HashSet<String>()
        runCatching { vfs.list(parent).forEach { taken += it.name } }

        var done = 0
        for (src in items) {
            currentCoroutineContext().ensureActive()
            ledger.progress(id, done / items.size.toFloat(), src.name)
            val name = dev.niccc2007.filet.browser.DuplicateName.of(src.name, taken)
            runCatching {
                withContext(Dispatchers.IO) { vfs.copy(src, parent, name) }
            }.onSuccess {
                done++
                taken += name
                made += parent.child(name)
            }.onFailure {
                failed += src to readable(it)
                ActionGate.fromFailure(FileAction.CREATE_FILE, parent, it, vfs.isRemote(parent))
                    ?.let { d -> denials += d }
            }
        }
        report(id, done, failed)
        announce(made)
        return OpResult(done, failed, denials)
    }

    /** Everything refused for the same reason, before anything was touched. */
    private fun refused(items: List<VPath>, denial: Denial) =
        OpResult(0, items.map { it to denial.short }, listOf(denial))

    /** The same, for an operation that produces one thing rather than a batch. */
    private fun refused(item: VPath, denial: Denial) = refused(listOf(item), denial)

    /** @return the name to write under, or null to keep the source name. */
    private suspend fun resolveName(src: VPath, into: VPath, policy: Conflict): String? {
        if (vfs.stat(into.child(src.name)) == null) return null
        return when (policy) {
            Conflict.RENAME -> vfs.uniqueChild(into, src.name).name
            Conflict.OVERWRITE -> { vfs.delete(into.child(src.name), recursive = true); null }
            Conflict.SKIP -> throw VfsException.AlreadyExists(into.child(src.name))
        }
    }

    suspend fun delete(items: List<VPath>): OpResult {
        items.firstNotNullOfOrNull { permit(FileAction.DELETE, it) }?.let { return refused(items, it) }
        val id = ledger.start("Deleting ${items.size} item(s)")
        val failed = ArrayList<Pair<VPath, String>>()
        val removed = ArrayList<VPath>()
        var done = 0
        val denials = ArrayList<Denial>()
        for (p in items) {
            currentCoroutineContext().ensureActive()
            ledger.progress(id, done / items.size.toFloat(), p.name)
            runCatching { withContext(Dispatchers.IO) { vfs.delete(p, recursive = true) } }
                .onSuccess { done++; removed += p }
                .onFailure {
                    failed += p to readable(it)
                    ActionGate.fromFailure(FileAction.DELETE, p, it, vfs.isRemote(p))
                        ?.let { d -> denials += d }
                }
        }
        report(id, done, failed)
        // Announced even though it is gone - that is how the media index drops it, and an
        // unannounced delete is a thumbnail in the gallery for a file that no longer exists.
        announce(removed)
        return OpResult(done, failed, denials)
    }

    /**
     * Pack [items] into an archive at [dest], in [format].
     *
     * Streams entry by entry through the VFS, so the source can be an SD card over SAF and the
     * destination internal storage without either side being special-cased. The format writers
     * live in `core-vfs` beside the readers, because a writer that stores a member path the
     * reader normalises differently produces an archive Filet itself cannot browse.
     *
     * 7z is the one that cannot go straight out: its writer seeks back to build an index, so
     * it is built in the cache and moved into place. That is slower and uses the space twice,
     * which is why it is not the default.
     */
    suspend fun compress(
        items: List<VNode>,
        dest: VPath,
        format: ArchiveFormat,
        scratch: (String) -> VPath,
        options: ArchiveOptions = ArchiveOptions.NONE,
    ): OpResult {
        val into = dest.parent ?: dest
        permit(FileAction.CREATE_FILE, into)?.let { return refused(dest, it) }
        val id = ledger.start("Compressing ${items.size} item(s)", dest.name)
        val failed = ArrayList<Pair<VPath, String>>()
        var count = 0
        try {
            // Bug identified here: `ArchiveWriter.writeToPath` - the 7z branch below - ran on
            // whatever dispatcher the caller was on, and the view model launches into
            // viewModelScope, which is the main one. So compressing to 7z did the entire
            // LZMA2 pass on the thread that draws, and the app was unresponsive from the tap
            // until the archive existed. The progress row it was writing to the whole time
            // never got a frame to draw in.
            //
            // The stream branch beside it was already dispatched, which is why this survived
            // review: the two branches looked alike and only one of them was safe. Everything
            // in here is dispatched now rather than the one call, so the next branch added
            // cannot be wrong in the same way. The VFS calls do not need it - the providers
            // dispatch their own IO - but being inside it costs nothing and removes the
            // question.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sources = ArrayList<ArchiveSource>(items.size * 4)
            for (node in items) {
                currentCoroutineContext().ensureActive()
                runCatching { collect(node, node.name, sources) }
                    .onSuccess { count++ }
                    .onFailure { failed += node.path to readable(it) }
            }
            if (ArchiveWriter.needsRealFile(format, options)) {
                // 7z seeks back through what it has written to build its index, so it cannot
                // go straight down a VFS stream. Built in app-private scratch space, then
                // copied into place - which is also why it is not the default.
                val tmp = scratch(dest.name)
                val os = vfs.osPath(tmp)
                    ?: throw VfsException.Unsupported("Nowhere to build a 7z on this device.")
                try {
                    ArchiveWriter.writeToPath(format, os, sources, options) { ledger.progress(id, null, it) }
                    // Off the caller's thread - see the note in `Vfs.copyNode`. An archive
                    // written to a mounted share is a socket write like any other.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vfs.openWrite(dest).use { out ->
                            vfs.openRead(tmp).use { it.copyTo(out) }
                        }
                    }
                } finally {
                    runCatching { vfs.delete(tmp) }
                }
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    vfs.openWrite(dest).use { out ->
                        ArchiveWriter.writeToStream(format, out, sources, options) { ledger.progress(id, null, it) }
                    }
                }
            }
            }
        } catch (e: Throwable) {
            // A half-written archive is worse than none: it looks like a file and opens as
            // rubbish. Best effort, because the failure may be that the volume is full.
            runCatching { vfs.delete(dest) }
            ledger.fail(id, readable(e))
            return OpResult(count, failed + (dest to readable(e)))
        }
        report(id, count, failed)
        return OpResult(count, failed)
    }

    /**
     * Walk a selection into a flat list of members.
     *
     * Folders are emitted before their contents and with an entry of their own, so an empty
     * folder survives the round trip - the one thing a flattened listing usually loses.
     */
    private suspend fun collect(node: VNode, entryPath: String, into: MutableList<ArchiveSource>) {
        currentCoroutineContext().ensureActive()
        if (node.isDir) {
            into += ArchiveSource(entryPath, isDir = true, size = -1L, mtime = node.mtime)
            for (child in vfs.list(node.path)) collect(child, "$entryPath/${child.name}", into)
            return
        }
        val path = node.path
        // Opened when the writer reaches it, which is already inside the dispatch above - but
        // named here too, because a lambda's thread is decided by whoever calls it and that is
        // not visible from where it is written.
        into += ArchiveSource(entryPath, isDir = false, size = node.size, mtime = node.mtime) {
            withContext(Dispatchers.IO) { vfs.openRead(path) }
        }
    }

    /**
     * A folder name in [into] that is not taken.
     *
     * Extracting twice used to merge the second archive into the first one's folder, which
     * silently overwrote same-named members. Numbering keeps both.
     */
    private suspend fun uniqueName(into: VPath, base: String): String {
        var n = 1
        while (n < 1000) {
            val candidate = if (n == 1) base else "$base ($n)"
            if (vfs.stat(into.child(candidate)) == null) return candidate
            n++
        }
        return "$base (${System.currentTimeMillis()})"
    }

    /** Copy everything under [archiveRoot] (a mounted archive path) out to [into]. */
    suspend fun extract(archiveRoot: VPath, into: VPath, label: String): OpResult {
        val id = ledger.start("Extracting", label)
        val failed = ArrayList<Pair<VPath, String>>()
        var count = 0
        try {
            // The whole suffix, so a .tar.gz extracts into "photos" rather than
            // "photos.tar" - which reads as a step in unwrapping rather than the contents.
            val dest = into.child(uniqueName(into, Archives.baseName(label)))
            if (vfs.stat(dest) == null) vfs.create(dest, isDir = true)
            for (child in vfs.list(archiveRoot)) {
                currentCoroutineContext().ensureActive()
                ledger.progress(id, null, child.name)
                runCatching { vfs.copy(child.path, dest) }
                    .onSuccess { count++ }
                    .onFailure { failed += child.path to readable(it) }
            }
        } catch (e: Throwable) {
            ledger.fail(id, readable(e))
            return OpResult(count, failed + (archiveRoot to readable(e)))
        }
        report(id, count, failed)
        return OpResult(count, failed)
    }

    private suspend fun batch(
        items: List<VPath>,
        verb: String,
        into: VPath,
        action: FileAction,
        each: suspend (VPath, ((Progress) -> Unit)) -> VPath?,
    ): OpResult {
        val id = ledger.start("$verb ${items.size} item(s)", into.name)
        val failed = ArrayList<Pair<VPath, String>>()
        // Both ends of every item: where it landed, and where it came from. The source matters
        // because a move leaves nothing behind and the index has to be told that too.
        val touched = ArrayList<VPath>()
        val denials = ArrayList<Denial>()
        var done = 0
        for (src in items) {
            currentCoroutineContext().ensureActive()
            runCatching {
                each(src) { p ->
                    val frac = if (p.total > 0) (p.done.toFloat() / p.total) else null
                    ledger.progress(id, frac, p.currentName)
                }
            }.onSuccess { landed ->
                done++
                touched += src
                landed?.let { touched += it }
            }.onFailure {
                failed += src to readable(it)
                // A host can accept the connection and still refuse one file. Declared
                // capabilities cannot know that, so it is caught on the way out instead.
                ActionGate.fromFailure(action, into, it, vfs.isRemote(into))
                    ?.let { d -> denials += d }
            }
        }
        report(id, done, failed)
        // Partial batches announce too: the items that succeeded were genuinely written, and
        // those are exactly the ones the gallery would otherwise be missing.
        announce(touched)
        return OpResult(done, failed, denials)
    }

    private fun report(id: Long, done: Int, failed: List<Pair<VPath, String>>) {
        if (failed.isEmpty()) ledger.finish(id, "$done item(s)")
        else ledger.fail(id, "${failed.size} failed, $done succeeded")
    }

    companion object {
        private val ALREADY_COMPRESSED = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "mp3", "opus", "ogg", "m4a",
            "aac", "flac", "mp4", "mkv", "webm", "mov", "avi", "zip", "apk", "7z", "rar", "gz", "xz",
        )

        fun readable(t: Throwable): String = when (t) {
            is VfsException.NotFound -> "no longer exists"
            is VfsException.AlreadyExists -> "already exists there"
            is VfsException.AccessDenied -> "permission denied"
            is VfsException.NotADirectory -> "not a folder"
            is VfsException.IsADirectory -> "is a folder"
            is VfsException.Unsupported -> t.message ?: "not supported"
            else -> t.message ?: t::class.simpleName ?: "failed"
        }
    }
}

/**
 * Pick a name that does not collide, the way a desktop does: `report (2).txt`.
 *
 * Refusing a paste because one of forty files collides is the behaviour every user hates;
 * silently overwriting is the behaviour that loses data. This is the third option.
 */
suspend fun Vfs.uniqueChild(dir: VPath, name: String): VPath {
    if (stat(dir.child(name)) == null) return dir.child(name)
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (i in 2..999) {
        val candidate = dir.child("$stem ($i)$ext")
        if (stat(candidate) == null) return candidate
    }
    throw VfsException.AlreadyExists(dir.child(name))
}
