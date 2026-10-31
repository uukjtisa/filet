package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobKind
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
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.ArchiveSource
import dev.niccc2007.filet.vfs.provider.ArchiveWriter
import dev.niccc2007.filet.vfs.provider.Archives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the clipboard holds and what pasting it should do. */
enum class PendingOp { COPY, MOVE }

/** What to do when a destination name is already taken. */
enum class Conflict { RENAME, OVERWRITE, SKIP }


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
     * Starts work outside whatever screen asked for it, for Retry and Run again on an activity
     * row. Null in tests, which then offer neither.
     */
    private val relaunch: ((suspend () -> Unit) -> Unit)? = null,
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
        return batch(items, "Copying", into, FileAction.CREATE_FILE, JobKind.COPY, { again -> copy(again, into, onConflict) }) { src, prog, _ ->
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
        // Run again only retries what failed: what moved is no longer where it was.
        return batch(items, "Moving", into, FileAction.CREATE_FILE, JobKind.MOVE, { again -> move(again, into, onConflict) }, retryOnly = true) { src, prog, pending ->
            when {
                pending == null -> vfs.move(src, into, resolveName(src, into, onConflict), prog).path
                // Into an archive the copy is only there once the archive is written, at the end
                // of the batch. The original goes after that and never before.
                into.scheme == ArchiveProvider.SCHEME && !sameArchive(src, into) ->
                    vfs.copy(src, into, resolveName(src, into, onConflict), prog).path.also { pending.removeAfter += src }
                into.scheme == ArchiveProvider.SCHEME -> vfs.move(src, into, resolveName(src, into, onConflict), prog).path
                // Out of an archive: a file this replaces is set aside, not deleted, until the
                // archive is written - a move that does not finish puts it back.
                else -> vfs.move(src, into, resolveParked(src, into, onConflict, pending), prog).path
            }
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

        // Every long operation registers the coroutine it runs on, which is what puts a Stop
        // on its activity row. Stopping leaves whatever was done already and says so - these
        // all check for cancellation between items, so the row reports a real count rather
        // than pretending the work unwound itself.
        val id = ledger.start(
            "Copying ${items.size} item(s)", parent.name,
            cancel = currentCoroutineContext()[kotlinx.coroutines.Job],
            opened = parent.toString(),
            kind = JobKind.COPY,
            from = parent.toString(),
            to = parent.toString(),
            totalItems = items.size,
            pausable = true,
        )
        val failed = ArrayList<Pair<VPath, String>>()
        val denials = ArrayList<Denial>()
        val made = ArrayList<VPath>()
        val taken = HashSet<String>()
        runCatching { vfs.list(parent).forEach { taken += it.name } }

        var done = 0
        for (src in items) {
            ledger.checkpoint(id)
            ledger.progress(id, done / items.size.toFloat(), src.name, doneItems = done + failed.size)
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
        offerAgain(id) { duplicate(items) }
        announce(made)
        return OpResult(done, failed, denials)
    }

    /**
     * Put Retry or Run again on a finished row: the same work, started outside the screen that
     * asked, as a row of its own. Nothing when there is no way to start work - a test.
     */
    private fun offerAgain(id: Long, block: suspend () -> Unit) {
        val r = relaunch ?: return
        ledger.runAgainWith(id) { r(block) }
    }

    /** Everything refused for the same reason, before anything was touched. */
    private fun refused(items: List<VPath>, denial: Denial) =
        OpResult(0, items.map { it to denial.short }, listOf(denial))

    /** The same, for an operation that produces one thing rather than a batch. */
    private fun refused(item: VPath, denial: Denial) = refused(listOf(item), denial)

    /**
     * What a batch written at its end - into or out of an archive - leaves to do once it is
     * written, or to undo when it is not.
     */
    private class Pending {
        /** Originals of a move into an archive, deleted once it is written. */
        val removeAfter = ArrayList<VPath>()

        /** Files a move out of an archive is replacing, set aside, with the names they had. */
        val parked = ArrayList<Pair<VPath, String>>()
    }

    /**
     * [resolveName], except that a file to be replaced is renamed aside rather than deleted.
     *
     * Bug identified here, in advance: a move out of an archive that fails part way removes the
     * copies it made, and a copy that had replaced a file would take that file with it - the
     * replacing delete had already happened before the copy began. A rename is undone by a
     * rename; a delete is not undone at all.
     */
    private suspend fun resolveParked(src: VPath, into: VPath, policy: Conflict, pending: Pending): String? {
        val existing = into.child(src.name)
        if (policy != Conflict.OVERWRITE || vfs.stat(existing) == null) return resolveName(src, into, policy)
        val aside = vfs.uniqueChild(into, ".${src.name}.replaced").name
        vfs.rename(existing, aside)
        pending.parked += into.child(aside) to src.name
        return null
    }

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
        val id = ledger.start(
            "Deleting ${items.size} item(s)",
            cancel = currentCoroutineContext()[kotlinx.coroutines.Job],
            kind = JobKind.DELETE,
            from = items.firstOrNull()?.parent?.toString().orEmpty(),
            totalItems = items.size,
            pausable = true,
        )
        if (items.isNotEmpty() && items.all { sameArchive(it, items.first()) }) return deleteInArchive(id, items)
        val failed = ArrayList<Pair<VPath, String>>()
        val removed = ArrayList<VPath>()
        var done = 0
        val denials = ArrayList<Denial>()
        for (p in items) {
            ledger.checkpoint(id)
            ledger.progress(id, done / items.size.toFloat(), p.name, doneItems = done + failed.size)
            runCatching { withContext(Dispatchers.IO) { vfs.delete(p, recursive = true) } }
                .onSuccess { done++; removed += p }
                .onFailure {
                    failed += p to readable(it)
                    ActionGate.fromFailure(FileAction.DELETE, p, it, vfs.isRemote(p))
                        ?.let { d -> denials += d }
                }
        }
        report(id, done, failed)
        // Retry only: what was deleted is gone, so there is nothing to run again.
        if (failed.isNotEmpty()) offerAgain(id) { delete(failed.map { it.first }) }
        // Announced even though it is gone - that is how the media index drops it, and an
        // unannounced delete is a thumbnail in the gallery for a file that no longer exists.
        announce(removed)
        return OpResult(done, failed, denials)
    }

    /**
     * [items] deleted from one archive in one rewrite of it, all of them or none.
     *
     * Deleting twelve files from a 7z one at a time is twelve rewrites of the whole archive; and
     * stopped at the fifth, it leaves an archive that is neither the one there was nor the one
     * asked for.
     */
    private suspend fun deleteInArchive(id: Long, items: List<VPath>): OpResult {
        val archive = archiveName(items.first())
        var at: VPath? = null
        val written = runCatching {
            vfs.batch(items.first(), onCommit = { f -> ledger.progress(id, f, "Writing $archive", doneItems = items.size) }) {
                for ((i, p) in items.withIndex()) {
                    ledger.checkpoint(id)
                    ledger.progress(id, i / items.size.toFloat(), p.name, doneItems = i)
                    try {
                        vfs.delete(p, recursive = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        at = p
                        throw e
                    }
                }
            }
        }
        written.exceptionOrNull()?.let { cause ->
            if (cause is CancellationException) throw cause
            val r = nothingDone(id, items, archive, at, cause, "nothing was deleted", FileAction.DELETE, items.first())
            offerAgain(id) { delete(items) }
            return r
        }
        report(id, items.size, emptyList())
        announce(items)
        return OpResult(items.size, emptyList())
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
        // With its coroutine, so the row can be stopped. Compressing is the longest thing
        // this app does - an LZMA2 pass over a folder of video is minutes - and it was the one
        // operation with no way to call it off short of killing the app.
        val id = ledger.start(
            "Compressing ${items.size} item(s)", dest.name,
            cancel = currentCoroutineContext()[kotlinx.coroutines.Job],
            opened = dest.toString(),
            kind = JobKind.COMPRESS,
            from = items.firstOrNull()?.path?.parent?.toString().orEmpty(),
            to = dest.toString(),
            pausable = true,
        )
        // The writer reports each entry as it starts it, on the IO thread, which is where a
        // pause holds - between one member and the next.
        var entries = 0
        val onEntry: (String) -> Unit = { name ->
            ledger.holdIfPaused(id)
            entries++
            ledger.progress(id, null, name, doneItems = entries)
        }
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
                    ArchiveWriter.writeToPath(format, os, sources, options, onEntry)
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
                        ArchiveWriter.writeToStream(format, out, sources, options, onEntry)
                    }
                }
            }
            }
        } catch (e: Throwable) {
            // A half-written archive is worse than none: it looks like a file and opens as
            // rubbish. Best effort, because the failure may be that the volume is full.
            runCatching { vfs.delete(dest) }
            ledger.fail(id, readable(e))
            // The half-written archive was removed, so the same work can simply be done again.
            offerAgain(id) { compress(items, dest, format, scratch, options) }
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
        val id = ledger.start(
            "Extracting", label,
            cancel = currentCoroutineContext()[kotlinx.coroutines.Job],
            opened = into.toString(),
            kind = JobKind.EXTRACT,
            from = archiveRoot.toString(),
            to = into.toString(),
            pausable = true,
        )
        val failed = ArrayList<Pair<VPath, String>>()
        var count = 0
        try {
            // The whole suffix, so a .tar.gz extracts into "photos" rather than
            // "photos.tar" - which reads as a step in unwrapping rather than the contents.
            val dest = into.child(uniqueName(into, Archives.baseName(label)))
            if (vfs.stat(dest) == null) vfs.create(dest, isDir = true)
            for (child in vfs.list(archiveRoot)) {
                ledger.checkpoint(id)
                ledger.progress(id, null, child.name, doneItems = count + failed.size)
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

    /**
     * @param again the same operation over a list of items, for Retry and Run again.
     * @param retryOnly offer only Retry, over what failed - for an operation whose successes
     *   cannot be done twice.
     */
    private suspend fun batch(
        items: List<VPath>,
        verb: String,
        into: VPath,
        action: FileAction,
        kind: JobKind,
        again: suspend (List<VPath>) -> OpResult,
        retryOnly: Boolean = false,
        /**
         * One item. The [Pending] is there only when the batch is written at its end - into an
         * archive, or a move out of one - and takes what has to wait for that.
         */
        each: suspend (VPath, ((Progress) -> Unit), Pending?) -> VPath?,
    ): OpResult {
        val id = ledger.start(
            "$verb ${items.size} item(s)", into.name,
            cancel = currentCoroutineContext()[kotlinx.coroutines.Job],
            opened = into.toString(),
            kind = kind,
            from = items.firstOrNull()?.parent?.toString().orEmpty(),
            to = into.toString(),
            totalItems = items.size,
            pausable = true,
        )
        if (into.scheme == ArchiveProvider.SCHEME) return intoArchive(id, items, into, action, again, retryOnly, each)
        if (kind == JobKind.MOVE && items.isNotEmpty() && items.all { sameArchive(it, items.first()) }) {
            return outOfArchive(id, items, into, action, again, each)
        }
        val failed = ArrayList<Pair<VPath, String>>()
        // Both ends of every item: where it landed, and where it came from. The source matters
        // because a move leaves nothing behind and the index has to be told that too.
        val touched = ArrayList<VPath>()
        val denials = ArrayList<Denial>()
        var done = 0
        // Bytes of the items already finished; the copy counts each item from zero.
        var before = 0L
        for (src in items) {
            ledger.checkpoint(id)
            var file = ""
            var moved = 0L
            runCatching {
                each(src, { p ->
                    // A pause holds when the next file inside a folder starts, so a folder of
                    // a thousand photos pauses within one photo rather than at the end of it.
                    if (p.currentName != file) {
                        file = p.currentName
                        ledger.holdIfPaused(id)
                    }
                    moved = p.done
                    val within = if (p.total > 0) (p.done.toFloat() / p.total) else 0f
                    ledger.progress(
                        id, (done + failed.size + within) / items.size, p.currentName,
                        doneItems = done + failed.size, doneBytes = before + p.done,
                    )
                }, null)
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
            before += moved
        }
        report(id, done, failed)
        when {
            failed.isNotEmpty() -> offerAgain(id) { again(failed.map { it.first }) }
            !retryOnly -> offerAgain(id) { again(items) }
        }
        // Partial batches announce too: the items that succeeded were genuinely written, and
        // those are exactly the ones the gallery would otherwise be missing.
        announce(touched)
        return OpResult(done, failed, denials)
    }

    /**
     * [items] into an archive: one rewrite of it, and all of them or none.
     *
     * The first item that fails stops the batch and the archive is left as it was - an archive
     * half-filled by a paste is neither the one there was nor the one asked for - and the report
     * names the item that stopped it. A Stop leaves it as it was too.
     */
    private suspend fun intoArchive(
        id: Long,
        items: List<VPath>,
        into: VPath,
        action: FileAction,
        again: suspend (List<VPath>) -> OpResult,
        retryOnly: Boolean,
        each: suspend (VPath, ((Progress) -> Unit), Pending?) -> VPath?,
    ): OpResult {
        val archive = archiveName(into)
        val landed = ArrayList<VPath>()
        val pending = Pending()
        val removeAfter = pending.removeAfter
        var at: VPath? = null
        var before = 0L
        val written = runCatching {
            vfs.batch(into, onCommit = { f -> ledger.progress(id, f, "Writing $archive", doneItems = items.size) }) {
                for ((i, src) in items.withIndex()) {
                    ledger.checkpoint(id)
                    var file = ""
                    var moved = 0L
                    val to = try {
                        each(src, { p ->
                            if (p.currentName != file) {
                                file = p.currentName
                                ledger.holdIfPaused(id)
                            }
                            moved = p.done
                            val within = if (p.total > 0) (p.done.toFloat() / p.total) else 0f
                            ledger.progress(id, (i + within) / items.size, p.currentName, doneItems = i, doneBytes = before + p.done)
                        }, pending)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        at = src
                        throw e
                    }
                    to?.let { landed += it }
                    before += moved
                }
            }
        }
        written.exceptionOrNull()?.let { cause ->
            if (cause is CancellationException) throw cause
            val r = nothingDone(id, items, archive, at, cause, "nothing was written", action, into)
            offerAgain(id) { again(items) }
            return r
        }
        // Written. Now, and only now, the originals of a move can go.
        val failed = ArrayList<Pair<VPath, String>>()
        val removed = ArrayList<VPath>()
        if (removeAfter.isNotEmpty() && removeAfter.all { sameArchive(it, removeAfter.first()) }) {
            // Out of another archive: its deletes are one rewrite of it too.
            runCatching { vfs.batch(removeAfter.first()) { removeAfter.forEach { vfs.delete(it, recursive = true) } } }
                .onSuccess { removed += removeAfter }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    removeAfter.forEach { failed += it to "copied in, but the original stayed: " + readable(e) }
                }
        } else {
            for (src in removeAfter) {
                runCatching { withContext(Dispatchers.IO) { vfs.delete(src, recursive = true) } }
                    .onSuccess { removed += src }
                    .onFailure { failed += src to "copied in, but the original stayed: " + readable(it) }
            }
        }
        val done = items.size - failed.size
        report(id, done, failed)
        if (!retryOnly) offerAgain(id) { again(items) }
        announce(landed + removed)
        return OpResult(done, failed)
    }

    /**
     * [items] moved out of one archive: copied out one by one, then taken out of it in one
     * rewrite, all of them or none.
     *
     * Bug identified here: each item's delete was a rewrite of its own, so moving twenty files
     * out of a large 7z rewrote all of it twenty times. Now the deletes wait for the batch. And
     * a move that fails part way, or is stopped, moves nothing: the archive is left as it was,
     * and the copies already made are removed again - every one of them is still in the archive,
     * so removing them loses nothing and the folder is not left holding half a move.
     */
    private suspend fun outOfArchive(
        id: Long,
        items: List<VPath>,
        into: VPath,
        action: FileAction,
        again: suspend (List<VPath>) -> OpResult,
        each: suspend (VPath, ((Progress) -> Unit), Pending?) -> VPath?,
    ): OpResult {
        val archive = archiveName(items.first())
        val landed = ArrayList<VPath>()
        val pending = Pending()
        var at: VPath? = null
        var before = 0L
        val written = runCatching {
            vfs.batch(items.first(), onCommit = { f -> ledger.progress(id, f, "Writing $archive", doneItems = items.size) }) {
                for ((i, src) in items.withIndex()) {
                    ledger.checkpoint(id)
                    var file = ""
                    var moved = 0L
                    val to = try {
                        each(src, { p ->
                            if (p.currentName != file) {
                                file = p.currentName
                                ledger.holdIfPaused(id)
                            }
                            moved = p.done
                            val within = if (p.total > 0) (p.done.toFloat() / p.total) else 0f
                            ledger.progress(id, (i + within) / items.size, p.currentName, doneItems = i, doneBytes = before + p.done)
                        }, pending)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        at = src
                        throw e
                    }
                    to?.let { landed += it }
                    before += moved
                }
            }
        }
        written.exceptionOrNull()?.let { cause ->
            // Run even when stopped: a Stop is the commonest way here, and a half move is what
            // it must not leave.
            withContext(NonCancellable + Dispatchers.IO) {
                for (p in landed) runCatching { vfs.delete(p, recursive = true) }
                // The copies are gone, so the names are free for what they had replaced.
                for ((aside, name) in pending.parked) {
                    runCatching { vfs.rename(aside, name) }.onFailure {
                        dev.niccc2007.filet.log.FiletLog.w("ops", "a replaced file stayed set aside as " + aside.name)
                    }
                }
            }
            if (cause is CancellationException) throw cause
            val r = nothingDone(id, items, archive, at, cause, "nothing was moved", action, into)
            offerAgain(id) { again(items) }
            return r
        }
        // Moved, so what the move replaced can go.
        withContext(Dispatchers.IO) {
            for ((aside, _) in pending.parked) runCatching { vfs.delete(aside, recursive = true) }
        }
        report(id, items.size, emptyList())
        announce(landed + items)
        return OpResult(items.size, emptyList())
    }

    /**
     * A whole-archive batch that changed nothing: every item reported, the one that stopped it
     * first, so the toast built from the first failure names it.
     */
    private fun nothingDone(
        id: Long,
        items: List<VPath>,
        archive: String,
        at: VPath?,
        cause: Throwable,
        nothing: String,
        action: FileAction,
        where: VPath,
    ): OpResult {
        val why = readable(cause)
        val reason = when {
            at == null -> "$archive could not be written: $why"
            items.size == 1 -> why
            else -> "${at.name}: $why, so $nothing"
        }
        ledger.fail(id, if (at != null && items.size == 1) "${at.name}: $why" else reason)
        val order = listOfNotNull(at) + items.filter { it != at }
        val denials = listOfNotNull(ActionGate.fromFailure(action, where, cause, vfs.isRemote(where)))
        return OpResult(0, order.map { it to reason }, denials)
    }

    private fun sameArchive(a: VPath, b: VPath): Boolean =
        a.scheme == ArchiveProvider.SCHEME && b.scheme == ArchiveProvider.SCHEME &&
            ArchiveProvider.split(a).first == ArchiveProvider.split(b).first

    private fun archiveName(p: VPath): String =
        ArchiveProvider.split(p).first.replace('\\', '/').substringAfterLast('/')

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
