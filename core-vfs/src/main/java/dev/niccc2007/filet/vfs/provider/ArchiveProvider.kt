package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import dev.niccc2007.filet.vfs.FileSystemProvider
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * An archive browsed as a directory tree: zip, tar, the tar-plus-compressor family, 7z, and a
 * single compressed file - and written into like one.
 *
 * Addressing: `zip:///path/to/archive.tar.gz!/inner/path`. The `!` separator is the JAR-URL
 * convention and keeps both halves readable in a breadcrumb. The scheme stays `zip` even for a
 * tar, because it is the *mount* that is being named and every pinned shortcut and saved tab
 * already spells it that way - renaming it to be tidier would break all of them.
 *
 * Which formats, and what each one costs, is [Archives] and `ArchiveReaders.kt`.
 *
 * ## Writing
 *
 * An archive is a folder everywhere, so a paste, a drop, a new folder, a delete and a rename
 * work inside one as they do anywhere - each as an [EditOp], carried out by [ArchiveCommit], the
 * same road an edited member's save takes. Inside a [batch] the edits wait and are written in one
 * rewrite when it ends, all of them or none; outside one, each is written as it is made. What a
 * format allows is [ArchiveWrites], and [writeRefusal] says why when the answer is no, before
 * anything is copied.
 *
 * @param stagedUnder folders whose archives are copies of a file that lives elsewhere - a remote
 *   file staged so its zip reader can seek. Writing into one would change the copy and not the
 *   file, so it is refused.
 */
class ArchiveProvider(private val stagedUnder: () -> List<String> = { emptyList() }) : FileSystemProvider {

    override val scheme: String = SCHEME
    override val capabilities: Set<Capability> = setOf(Capability.READ)

    override fun capabilitiesFor(path: VPath): Set<Capability> {
        val host = File(split(path).first)
        if (writeRefusal(path) != null) return capabilities
        return ArchiveWrites.capabilities(ArchiveWrites.kindOf(Archives.of(host.name)))
    }

    override fun writeRefusal(path: VPath): String? {
        val host = File(split(path).first)
        // Opened by its bytes under some other name: readable, and kept read-only, because the
        // writers choose what to write from the name and this name names nothing.
        if (Archives.of(host.name) == null) {
            formatOfFile(host)?.let { f ->
                return "${host.name} is a ${f.label} file under another name, so Filet opens it read-only. " +
                    "Rename it to .${f.extensions.first()} to change what is inside."
            }
        }
        ArchiveWrites.formatRefusal(host.name)?.let { return it }
        if (ArchiveWrites.kindOf(Archives.of(host.name)) == ArchiveWrites.Kind.STREAM) {
            val label = Archives.of(host.name)?.label ?: "compressed"
            return "A $label file holds exactly one file, so Filet can replace it but not add, remove or rename anything in it."
        }
        ArchiveWrites.stagedRefusal(host, stagedUnder())?.let { return it }
        return Refusals.of(host)
    }

    override suspend fun roots(): List<VNode> = emptyList()

    override suspend fun list(path: VPath): List<VNode> = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        val members = membersOf(archive, path)
        val writable = Capability.WRITE in capabilitiesFor(path)
        val prefix = if (inner.isEmpty()) "" else inner.trimEnd('/') + "/"
        val direct = LinkedHashMap<String, VNode>()
        for (m in members) {
            if (!m.name.startsWith(prefix) || m.name == prefix.trimEnd('/')) continue
            val rest = m.name.substring(prefix.length).trimEnd('/')
            if (rest.isEmpty()) continue
            val cut = rest.indexOf('/')
            if (cut < 0) {
                if (m.isDir) {
                    direct.getOrPut(rest) { VNode(path.child(rest), true, -1L, m.mtime, writable = writable) }
                } else {
                    direct[rest] = VNode(path.child(rest), false, m.size, m.mtime, writable = writable)
                }
            } else {
                // An implicit directory. Most zips omit explicit folder entries and most tars
                // include them, so a listing built only from real entries loses whole subtrees
                // on one format and duplicates them on the other.
                val dir = rest.substring(0, cut)
                direct.getOrPut(dir) { VNode(path.child(dir), true, -1L, m.mtime, writable = writable) }
            }
        }
        direct.values.toList()
    }

    override suspend fun stat(path: VPath): VNode? = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        if (inner.isEmpty()) {
            val f = File(archive)
            if (!f.isFile) recover(f)
            return@withContext if (f.isFile) VNode(path, true, -1L, f.lastModified()) else null
        }
        val members = membersOf(archive, path)
        val writable = Capability.WRITE in capabilitiesFor(path)
        members.firstOrNull { it.name == inner }?.let {
            return@withContext VNode(path, it.isDir, it.size, it.mtime, writable = writable)
        }
        val prefix = "$inner/"
        if (members.any { it.name.startsWith(prefix) }) {
            VNode(path, isDir = true, size = -1L, mtime = 0L, writable = writable)
        } else null
    }

    override suspend fun openRead(path: VPath): InputStream = withContext(Dispatchers.IO) {
        val (archive, inner) = split(path)
        // A file put in this batch and not yet written is read from where it waits.
        pending(archive)?.spoolOf(inner)?.let { return@withContext File(it).inputStream() }
        val reader = readerOf(archive, path)
        val stream = runCatching { reader.open(inner) }.getOrElse { throw VfsException.Io(path, it) }
        stream ?: throw VfsException.NotFound(path)
    }

    // ── writing ──

    override suspend fun openWrite(path: VPath, append: Boolean): OutputStream {
        if (append) throw VfsException.Unsupported("Filet cannot add to the end of a file inside an archive.")
        val (archive, inner) = split(path)
        refuseWrite(path, inner)
        val session = pending(archive)
        val spool = withContext(Dispatchers.IO) { Spool.create() }
        return object : FilterOutputStream(spool.outputStream().buffered()) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                super.close()
                val op = EditOp.Put(inner, spool.absolutePath, spool.length())
                // close() is not a suspend function, and outside a batch this is where the write
                // happens; Vfs calls it on the IO dispatcher.
                if (session != null) session.add(path, op, spool) else runBlocking { commitNow(path, archive, op, spool) }
            }
        }
    }

    override suspend fun create(path: VPath, isDir: Boolean): VNode {
        val (archive, inner) = split(path)
        refuseWrite(path, inner)
        if (isDir) {
            record(path, archive, EditOp.Mkdir(inner))
        } else {
            val spool = withContext(Dispatchers.IO) { Spool.create() }
            record(path, archive, EditOp.Put(inner, spool.absolutePath, 0L), spool)
        }
        return VNode(path, isDir, if (isDir) -1L else 0L, System.currentTimeMillis())
    }

    override suspend fun delete(path: VPath, recursive: Boolean) {
        val (archive, inner) = split(path)
        refuseWrite(path, inner)
        record(path, archive, EditOp.Delete(inner))
    }

    override suspend fun rename(path: VPath, newName: String): VNode {
        if (newName.isBlank() || newName.contains('/')) throw VfsException.Unsupported("invalid name: $newName")
        val (archive, inner) = split(path)
        refuseWrite(path, inner)
        val parent = inner.substringBeforeLast('/', "")
        val to = if (parent.isEmpty()) newName else "$parent/$newName"
        val isDir = stat(path)?.isDir ?: throw VfsException.NotFound(path)
        record(path, archive, EditOp.Rename(inner, to))
        return VNode(path.parent?.child(newName) ?: path, isDir, -1L, System.currentTimeMillis())
    }

    /** A move from one place in an archive to another in the same archive is a rename of its path. */
    override suspend fun moveWithin(from: VPath, to: VPath): VNode? {
        val (a, innerFrom) = split(from)
        val (b, innerTo) = split(to)
        if (a != b || innerFrom.isEmpty() || innerTo.isEmpty()) return null
        refuseWrite(from, innerFrom)
        val isDir = stat(from)?.isDir ?: throw VfsException.NotFound(from)
        record(from, a, EditOp.Rename(innerFrom, innerTo))
        return VNode(to, isDir, -1L, System.currentTimeMillis())
    }

    /**
     * Everything in [block] as one rewrite of the archive [path] is in, written when it ends.
     *
     * All or none: an edit refused part way, a failure, or a Stop leaves the archive exactly as
     * it was and nothing of the batch in it.
     */
    override suspend fun <T> batch(path: VPath, onCommit: (Float) -> Unit, block: suspend () -> T): T {
        if (path.scheme != SCHEME) return block()
        val (archive, _) = split(path)
        // Already inside one for this archive: the edits join it.
        if (coroutineContext[Batch]?.sessions?.containsKey(archive) == true) return block()
        val host = File(archive)
        val session = withContext(Dispatchers.IO) {
            recover(host)
            // Refused before anything is read or copied. An encrypted zip is one that the JDK's
            // own reader will not even list, so reading first turned the refusal into an I/O error.
            writeRefusal(path)?.let { throw VfsException.Unsupported(it) }
            Session(host, membersOf(archive, path))
        }
        val sessions = HashMap(coroutineContext[Batch]?.sessions.orEmpty())
        sessions[archive] = session
        try {
            val result = withContext(Batch(sessions)) { block() }
            if (session.ops.isNotEmpty()) {
                val ctx = coroutineContext
                withContext(Dispatchers.IO) {
                    commit(path, host, session.ops, onCommit) { !ctx.isActive }
                }
            }
            return result
        } finally {
            session.discard()
        }
    }

    private fun refuseWrite(path: VPath, inner: String) {
        if (inner.isEmpty()) throw VfsException.Unsupported("That is the archive itself, not something inside it.")
        writeRefusal(path)?.let { throw VfsException.Unsupported(it) }
    }

    private suspend fun pending(archive: String): Session? = coroutineContext[Batch]?.sessions?.get(archive)

    /** Into the open batch for this archive, or written now when there is none. */
    private suspend fun record(path: VPath, archive: String, op: EditOp, spool: File? = null) {
        val session = pending(archive)
        if (session != null) session.add(path, op, spool)
        else commitNow(path, archive, op, spool)
    }

    private suspend fun commitNow(path: VPath, archive: String, op: EditOp, spool: File?) {
        try {
            val ctx = coroutineContext
            withContext(Dispatchers.IO) { commit(path, File(archive), listOf(op), {}) { !ctx.isActive } }
        } finally {
            spool?.delete()
        }
    }

    private fun commit(path: VPath, host: File, ops: List<EditOp>, onCommit: (Float) -> Unit, cancelled: () -> Boolean) {
        writeRefusal(path)?.let { throw VfsException.Unsupported(it) }
        try {
            ArchiveCommit.run(host, ops, onCommit, cancelled)
        } catch (e: PlanException) {
            throw planFailure(path, e)
        } catch (e: IOException) {
            throw VfsException.Io(path, e)
        } finally {
            Refusals.forget(host)
        }
    }

    /**
     * The edits waiting for one archive in a [batch], checked as they arrive against the archive
     * as it will be, so a clash is refused at the item that caused it.
     */
    private inner class Session(val host: File, base: List<Member>) {
        private val baseMembers = base
        private val plan = ArchivePlan.State(base)
        val ops = ArrayList<EditOp>()
        private val spools = ArrayList<File>()

        @Synchronized
        fun add(path: VPath, op: EditOp, spool: File?) {
            spool?.let { spools += it }
            val format = Archives.of(host.name)
            ArchiveWrites.opRefusal(ArchiveWrites.kindOf(format), format?.label ?: "this", baseMembers, op)
                ?.let { throw VfsException.Unsupported(it) }
            try {
                plan.add(op)
            } catch (e: PlanException) {
                throw planFailure(path, e)
            }
            ops += op
        }

        @Synchronized
        fun members(): List<Member> = ArchivePlan.view(baseMembers, plan.outcomes, System.currentTimeMillis())

        @Synchronized
        fun spoolOf(inner: String): String? =
            (plan.outcomes.firstOrNull { it.name == inner } as? Outcome.Fresh)?.spool

        @Synchronized
        fun discard() {
            spools.forEach { it.delete() }
            spools.clear()
        }
    }

    private class Batch(val sessions: Map<String, Session>) : AbstractCoroutineContextElement(Batch) {
        companion object Key : CoroutineContext.Key<Batch>
    }

    private fun planFailure(path: VPath, e: PlanException): VfsException = when (e.why) {
        PlanException.Why.NOT_FOUND -> VfsException.NotFound(path)
        PlanException.Why.EXISTS -> VfsException.AlreadyExists(path)
        PlanException.Why.REFUSED -> VfsException.Unsupported(e.message ?: "That cannot go there.")
    }

    /** A swap killed part way, put right before the archive is read or written. See [ArchiveSwap]. */
    private fun recover(host: File) {
        val dir = host.absoluteFile.parentFile ?: return
        runCatching { ArchiveSwap.recover(ArchiveSwap.filesIn(dir), host.name) }
    }

    /**
     * The reader for an archive, or a refusal that names the reason.
     *
     * A format Filet deliberately does not read - RAR - fails with its own sentence rather
     * than with "not found", because "not found" on a file you can see is the least useful
     * error an app can produce.
     */
    private fun readerOf(archive: String, path: VPath): ArchiveReader {
        val asked = File(archive)
        // Opened after a rewrite was killed between moving the original aside and putting the
        // new one in: the original goes back first, so the archive is there to open.
        if (!asked.exists()) recover(asked)

        // Tapping any part of a multi-part set opens the whole archive. Which file actually
        // does the opening differs per scheme, and for a numbered set the parts have to be
        // joined first - see MultiPartOpen.
        val set = MultiPartOpen.resolve(asked)
        set.refusal?.let { throw VfsException.Unsupported(it) }

        val file = when {
            set.join != null -> JoinedSets.materialise(set.setName ?: asked.name, set.join)
            else -> set.openWith
        }

        val format = formatOfFile(file)
        format?.refusal?.let { throw VfsException.Unsupported(it) }
        if (format == null) throw VfsException.Unsupported("${asked.name} is not an archive Filet knows.")

        // A zip volume set needs a reader that follows volumes; the ordinary one looks for the
        // central directory in the file it was handed and a set puts it in the last part.
        if (set.style == PartStyle.ZIP_VOLUMES) return SplitZipReader(file)

        return readerFor(file, format) ?: throw VfsException.Unsupported(format.label + " is not readable here.")
    }

    /** What is in the archive, as this coroutine's batch will leave it when there is one. */
    private suspend fun membersOf(archive: String, path: VPath): List<Member> {
        pending(archive)?.let { return it.members() }
        return runCatching { readerOf(archive, path).members() }.getOrElse {
            if (it is VfsException) throw it else throw VfsException.Io(path, it)
        }
    }

    /**
     * The refusals that need a look inside the archive or beside it - encrypted, split into
     * parts - remembered by path, length and modification time, because the question is asked
     * every time a menu is drawn and the answer only changes when the file does.
     */
    private object Refusals {
        private val seen: MutableMap<String, String> =
            Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 16
            })

        fun of(host: File): String? {
            val key = "${host.absolutePath}|${host.length()}|${host.lastModified()}"
            val known = seen[key] ?: (measure(host) ?: "").also { seen[key] = it }
            return known.ifEmpty { null }
        }

        fun forget(host: File) {
            val prefix = host.absolutePath + "|"
            synchronized(seen) { seen.keys.removeAll { it.startsWith(prefix) } }
        }

        private fun measure(host: File): String? {
            if (!host.isFile) return null
            val set = runCatching { MultiPartOpen.resolve(host) }.getOrNull()
            if (set?.isSet == true) return SPLIT
            // The editor's sentences end in "save the edited file somewhere else", which is
            // advice for a save and makes no sense on a paste.
            return when (val r = runCatching { EditCosts.refusalFor(host) }.getOrNull()) {
                EditCosts.ENCRYPTED_ZIP -> ENCRYPTED_ZIP
                EditCosts.ENCRYPTED_7Z -> ENCRYPTED_7Z
                else -> r
            }
        }
    }

    companion object {
        const val SCHEME = "zip"
        private const val SEP = "!"

        private const val SPLIT =
            "This archive is split into parts. Join the parts into one file to change what is inside."

        private const val ENCRYPTED_ZIP =
            "This zip is encrypted. Filet cannot change what is inside without dropping the " +
                "protection on the rest. Extract what you need and change it there."

        private const val ENCRYPTED_7Z =
            "This 7z is encrypted. Filet cannot rebuild it without the password. Extract what " +
                "you need and change it there."

        /** `zip:///a/b.zip!/x/y` -> ("/a/b.zip", "x/y") */
        fun split(path: VPath): Pair<String, String> {
            val i = path.path.indexOf(SEP)
            val archive = if (i < 0) path.path else path.path.substring(0, i)
            val inner = if (i < 0) "" else path.path.substring(i + 1).trim('/')
            return osPath(archive) to inner
        }

        /**
         * Undo the forced leading separator on hosts that do not use one.
         *
         * Only ever strips it in front of a `X:` drive letter, so a genuine POSIX path such
         * as `/storage/emulated/0/a.zip` is returned untouched.
         */
        private fun osPath(p: String): String =
            if (p.length > 2 && p[0] == '/' && p[2] == ':') p.substring(1) else p

        /**
         * Build the VPath that opens [archivePath] as a directory.
         *
         * The leading separator is forced: a VPath is absolute by contract, and a host whose
         * paths start with a drive letter would otherwise produce an invalid one.
         */
        fun mount(archivePath: String): VPath {
            // A filesystem path, never a VPath's toString(). A scheme here can only be a
            // caller that reached for toString() instead of .path, and the result was a
            // container path nobody could read - the install of a split bundle failed with an
            // IO error naming the bundle, which looked like a broken file rather than a
            // broken address. Refusing outright turns that into a fault at the call site.
            require(!archivePath.contains("://")) {
                "mount() takes a filesystem path, not a VPath: $archivePath"
            }
            val abs = if (archivePath.startsWith("/")) archivePath else "/$archivePath"
            return VPath(SCHEME, "$abs$SEP/")
        }

        /**
         * Extensions treated as browsable containers.
         *
         * Kept for callers that only have an extension to hand. **Prefer
         * [Archives.canList] with the whole name** - this set cannot see a `.tar.gz`, because
         * by the time a name is reduced to its last dot that archive is a "gz".
         */
        val EXTENSIONS: Set<String> =
            Archives.ALL.filter { it.canList }.flatMapTo(HashSet()) { it.extensions }
    }
}
