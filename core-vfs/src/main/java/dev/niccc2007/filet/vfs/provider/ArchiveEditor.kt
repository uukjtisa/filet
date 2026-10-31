package dev.niccc2007.filet.vfs.provider

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Changing what is inside an existing archive: adding, replacing, deleting, renaming.
 *
 * Every change arrives as a list of [Outcome]s - the archive as it should be after the edit,
 * entry by entry, from [ArchivePlan] - and is written by the archive's family:
 *
 * - **zip, jar, apk, epub, cbz...** - patched. Every member that is kept is copied across as its
 *   already-compressed bytes (`addRawArchiveEntry`), renamed if it moved, so a 340-file archive
 *   with one file added compresses exactly one file.
 * - **7z and the tar family** - rewritten whole, streaming each member from the old archive into
 *   the new one. 7z is solid and a compressed tar is one stream: there is no member to leave
 *   alone. Not slow code - the format, and the save dialog says so with the size first.
 * - **gz, bz2, xz** - one file, so the only change is replacing it.
 * - **RAR** - never written.
 *
 * ## Never in place
 *
 * The new archive is written beside the old one and swapped in only when it is whole - see
 * [ArchiveSwap]. A cancelled job, a full disk or a killed process leaves the original exactly as
 * it was. An archive is usually the only copy of what is in it.
 */
object ArchiveEditor {

    /** What a save can offer for this archive. */
    fun modeFor(archiveName: String): EditMode = ArchiveCapabilities.editModeFor(archiveName)

    /**
     * Replace [member] inside [archive] with the bytes from [open]. The member must be there:
     * this is the save of an edited file, not an add.
     *
     * @throws IOException with a plain sentence when the format cannot be written, or the member
     *   is not in the archive.
     */
    @Throws(IOException::class)
    fun replace(archive: File, member: String, open: () -> InputStream) {
        val format = Archives.of(archive.name)
            ?: throw IOException("${archive.name} is not an archive Filet knows.")
        if (modeFor(archive.name) == EditMode.COPY_ONLY) {
            throw IOException(
                "Filet reads ${format.label} and cannot write it, so this file can only be saved somewhere else.",
            )
        }
        val want = normaliseMember(member)
        val members = membersOf(archive, format)
        if (members.none { it.name == want && !it.isDir }) throw IOException("$want is not in ${archive.name}")
        val spool = Spool.of(open)
        try {
            ArchiveCommit.run(archive, listOf(EditOp.Put(want, spool.absolutePath, spool.length())))
        } finally {
            spool.delete()
        }
    }

    /**
     * Write [outcomes] as [archive]'s new contents.
     *
     * @param members what the archive holds now, which the [Outcome.Keep]s refer to.
     * @param progress how far the rewrite has got, 0 to 1.
     * @param cancelled asked between entries and during each copy; true stops the rewrite and
     *   leaves the original.
     */
    internal fun rewrite(
        archive: File,
        format: ArchiveFormat,
        members: List<Member>,
        outcomes: List<Outcome>,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) {
        val dir = archive.absoluteFile.parentFile ?: throw IOException("${archive.name} has no folder")
        val fs = ArchiveSwap.filesIn(dir)
        val temp = File(dir, ArchiveSwap.editing(archive.name))
        val meter = Meter(members, outcomes, progress, cancelled)
        try {
            when (ArchiveWrites.kindOf(format)) {
                ArchiveWrites.Kind.ZIP -> writeZip(archive, temp, outcomes, meter)
                ArchiveWrites.Kind.TAR -> writeTar(format, archive, temp, outcomes, meter)
                ArchiveWrites.Kind.SEVEN_ZIP -> write7z(archive, temp, outcomes, meter)
                ArchiveWrites.Kind.STREAM -> writeStream(format, temp, outcomes)
                ArchiveWrites.Kind.NONE -> throw IOException("Filet reads ${format.label} and cannot write it.")
            }
            if (!temp.isFile || temp.length() == 0L) throw IOException("the rewritten archive came out empty")
            meter.stop()
            ArchiveSwap.swap(fs, archive.name)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** What is in [archive] now, by the same reader a listing uses. */
    internal fun membersOf(archive: File, format: ArchiveFormat): List<Member> =
        (readerFor(archive, format) ?: throw IOException("${format.label} is not readable here.")).members()

    // ── zip ──

    private fun writeZip(archive: File, temp: File, outcomes: List<Outcome>, meter: Meter) {
        ZipFile.builder().setFile(archive).get().use { zf ->
            val byName = HashMap<String, ZipArchiveEntry>()
            for (e in zf.entries) byName.putIfAbsent(normaliseMember(e.name), e)
            ZipArchiveOutputStream(temp).use { out ->
                for (o in outcomes) {
                    meter.stop()
                    when (o) {
                        is Outcome.Keep -> {
                            val e = byName[o.from] ?: continue
                            // Raw: the stored bytes go across untouched, under the new name when it moved.
                            val entry = if (o.name == o.from) e else renamed(e, o.name)
                            out.addRawArchiveEntry(entry, zf.getRawInputStream(e))
                            meter.done(o)
                        }
                        is Outcome.Fresh -> {
                            val fresh = ZipArchiveEntry(o.name)
                            fresh.method = ZipArchiveEntry.DEFLATED
                            fresh.time = System.currentTimeMillis()
                            out.putArchiveEntry(fresh)
                            File(o.spool).inputStream().use { meter.copy(it, out) }
                            out.closeArchiveEntry()
                            meter.done(o)
                        }
                        is Outcome.Dir -> {
                            out.putArchiveEntry(ZipArchiveEntry(o.name + "/"))
                            out.closeArchiveEntry()
                        }
                    }
                }
            }
        }
    }

    /**
     * [e] under another name, with everything a raw copy needs carried over. The extra fields
     * are not: one of them can be a Unicode path field holding the OLD name, which readers
     * prefer over the header and which would undo the rename.
     */
    private fun renamed(e: ZipArchiveEntry, name: String): ZipArchiveEntry {
        val n = ZipArchiveEntry(if (e.isDirectory) "$name/" else name)
        n.method = e.method
        n.crc = e.crc
        n.compressedSize = e.compressedSize
        n.size = e.size
        n.time = e.time
        n.internalAttributes = e.internalAttributes
        n.externalAttributes = e.externalAttributes
        n.generalPurposeBit = e.generalPurposeBit
        return n
    }

    // ── tar ──

    /**
     * A tar, through whichever compressor its extension names. A tar has no index, so the old
     * archive is read once from the front: every kept member is written as it passes, under its
     * new name if it moved, and the new files and folders follow.
     */
    private fun writeTar(format: ArchiveFormat, archive: File, temp: File, outcomes: List<Outcome>, meter: Meter) {
        val keeps = HashMap<String, Outcome.Keep>()
        for (o in outcomes) if (o is Outcome.Keep) keeps[o.from] = o
        openTar(format, archive).use { tin ->
            wrapOut(format, temp.outputStream().buffered()).use { raw ->
                TarArchiveOutputStream(raw).use { out ->
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    var entry = tin.nextEntry
                    while (entry != null) {
                        meter.stop()
                        val keep = keeps.remove(normaliseMember(entry.name))
                        if (keep != null) {
                            val name = if (entry.isDirectory) keep.name + "/" else keep.name
                            val copy = if (entry.isSymbolicLink || entry.isLink) {
                                val flag = if (entry.isSymbolicLink) TarConstants.LF_SYMLINK else TarConstants.LF_LINK
                                TarArchiveEntry(name, flag).also { it.linkName = entry.linkName }
                            } else {
                                TarArchiveEntry(name)
                            }
                            copy.mode = entry.mode
                            copy.size = if (entry.isFile) entry.size else 0L
                            copy.setModTime(entry.lastModifiedDate?.time ?: 0L)
                            out.putArchiveEntry(copy)
                            if (entry.isFile) meter.copy(tin, out)
                            out.closeArchiveEntry()
                            meter.done(keep)
                        }
                        entry = tin.nextEntry
                    }
                    for (o in outcomes) {
                        meter.stop()
                        when (o) {
                            is Outcome.Fresh -> {
                                val spool = File(o.spool)
                                // A tar header states the length up front and the reader believes
                                // it, so the size comes from the spool, which is whole by now.
                                val fresh = TarArchiveEntry(o.name)
                                fresh.size = spool.length()
                                fresh.setModTime(System.currentTimeMillis())
                                out.putArchiveEntry(fresh)
                                spool.inputStream().use { meter.copy(it, out) }
                                out.closeArchiveEntry()
                                meter.done(o)
                            }
                            is Outcome.Dir -> {
                                val dir = TarArchiveEntry(o.name + "/")
                                dir.setModTime(System.currentTimeMillis())
                                out.putArchiveEntry(dir)
                                out.closeArchiveEntry()
                            }
                            is Outcome.Keep -> Unit
                        }
                    }
                }
            }
        }
    }

    // ── 7z ──

    /**
     * 7z is solid by default, so this is a full rewrite whatever changed.
     *
     * Read in order with `getNextEntry` rather than `getInputStream(entry)`: in a solid block the
     * second reopens the block and decodes from its start for every member, which turns one pass
     * into a pass per file. Streamed through a buffer both ways - no member is ever held whole,
     * so a 4 GB file inside costs the same memory as a 4 KB one.
     */
    private fun write7z(archive: File, temp: File, outcomes: List<Outcome>, meter: Meter) {
        val keeps = HashMap<String, Outcome.Keep>()
        for (o in outcomes) if (o is Outcome.Keep) keeps[o.from] = o
        SevenZFile.builder().setFile(archive).get().use { z ->
            SevenZOutputFile(temp).use { out ->
                var entry = z.nextEntry
                val buf = ByteArray(BUFFER)
                while (entry != null) {
                    meter.stop()
                    val keep = keeps.remove(normaliseMember(entry.name))
                    if (keep != null) {
                        val fresh = SevenZArchiveEntry()
                        fresh.name = keep.name
                        fresh.isDirectory = entry.isDirectory
                        if (entry.hasLastModifiedDate) fresh.lastModifiedTime = entry.lastModifiedTime
                        out.putArchiveEntry(fresh)
                        if (!entry.isDirectory && entry.hasStream()) {
                            while (true) {
                                meter.stop()
                                val n = z.read(buf, 0, buf.size)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                meter.moved(n.toLong())
                            }
                        }
                        out.closeArchiveEntry()
                        meter.done(keep)
                    }
                    entry = z.nextEntry
                }
                for (o in outcomes) {
                    meter.stop()
                    when (o) {
                        is Outcome.Fresh -> {
                            val fresh = SevenZArchiveEntry()
                            fresh.name = o.name
                            fresh.lastModifiedTime = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())
                            out.putArchiveEntry(fresh)
                            File(o.spool).inputStream().use { input ->
                                while (true) {
                                    meter.stop()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    meter.moved(n.toLong())
                                }
                            }
                            out.closeArchiveEntry()
                            meter.done(o)
                        }
                        is Outcome.Dir -> {
                            val dir = SevenZArchiveEntry()
                            dir.name = o.name
                            dir.isDirectory = true
                            out.putArchiveEntry(dir)
                            out.closeArchiveEntry()
                        }
                        is Outcome.Keep -> Unit
                    }
                }
            }
        }
    }

    // ── a single compressed file ──

    /** `notes.txt.gz` holds exactly one file, so replacing it IS rewriting the archive. */
    private fun writeStream(format: ArchiveFormat, temp: File, outcomes: List<Outcome>) {
        val fresh = outcomes.singleOrNull() as? Outcome.Fresh
            ?: throw IOException("A ${format.label} file holds exactly one file, so Filet can only replace it.")
        wrapOut(format, temp.outputStream().buffered()).use { out ->
            File(fresh.spool).inputStream().use { it.copyTo(out, BUFFER) }
        }
    }

    private fun openTar(format: ArchiveFormat, archive: File): TarArchiveInputStream {
        val raw = archive.inputStream().buffered()
        val decoded: InputStream = when (format.id) {
            "tar.gz" -> GzipCompressorInputStream(raw, true)
            "tar.bz2" -> BZip2CompressorInputStream(raw, true)
            "tar.xz" -> XZCompressorInputStream(raw, true)
            else -> raw
        }
        return TarArchiveInputStream(decoded)
    }

    private fun wrapOut(format: ArchiveFormat, raw: OutputStream): OutputStream = when (format.id) {
        "tar.gz", "gz" -> GzipCompressorOutputStream(raw)
        "tar.bz2", "bz2" -> BZip2CompressorOutputStream(raw)
        "tar.xz", "xz" -> XZCompressorOutputStream(raw)
        else -> raw
    }

    private const val BUFFER = 64 * 1024

    /**
     * Progress by bytes, and the one place cancellation is asked. The weight of a kept zip member
     * is its stored size, since that is what is copied; everything else is its full size.
     */
    private class Meter(
        members: List<Member>,
        outcomes: List<Outcome>,
        private val progress: (Float) -> Unit,
        private val cancelled: () -> Boolean,
    ) {
        private val sizes = members.associate { it.name to it.size.coerceAtLeast(0L) }
        private val total = outcomes.sumOf { weight(it) }.coerceAtLeast(1L)
        private var done = 0L
        private var counted = 0L
        private var lastShown = -1

        private fun weight(o: Outcome): Long = when (o) {
            is Outcome.Keep -> sizes[o.from] ?: 0L
            is Outcome.Fresh -> o.size.coerceAtLeast(0L)
            is Outcome.Dir -> 0L
        }

        fun stop() {
            if (cancelled()) throw CancellationException("Stopped. The archive was left as it was.")
        }

        fun moved(n: Long) {
            counted += n
            show(done + counted)
        }

        fun done(o: Outcome) {
            done += weight(o)
            counted = 0L
            show(done)
        }

        fun copy(input: InputStream, out: OutputStream) {
            val buf = ByteArray(BUFFER)
            while (true) {
                stop()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                moved(n.toLong())
            }
        }

        private fun show(at: Long) {
            val pct = (at * 100 / total).toInt().coerceIn(0, 100)
            if (pct == lastShown) return
            lastShown = pct
            progress(pct / 100f)
        }
    }
}

/**
 * Bytes waiting to go into an archive.
 *
 * A file of their own in the system's temp folder, so the archive's folder never holds anything
 * but the archive and, for the moment of a swap, its replacement - and so the size is known
 * before a tar header has to state it.
 */
internal object Spool {
    fun create(): File = File.createTempFile("filet-put-", ".part")

    fun of(open: () -> InputStream): File {
        val f = create()
        try {
            open().use { input -> f.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
        return f
    }
}

/**
 * Applying edits to an archive on disk: the one road every write into an archive takes.
 *
 * One at a time per archive - two panes saving into the same zip at once would otherwise each
 * rewrite from the same original and the second would drop the first's change. The edits are
 * re-applied to what the archive holds AT THAT MOMENT, so a batch that waited for the lock still
 * lands on top of whatever was written while it waited.
 */
internal object ArchiveCommit {

    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(archive: File): Any = locks.getOrPut(archive.absolutePath) { Any() }

    /**
     * @throws IOException for a format or archive that cannot take the edit, with the reason.
     * @throws PlanException for an edit whose names do not work against the archive.
     */
    fun run(
        archive: File,
        ops: List<EditOp>,
        progress: (Float) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ) = synchronized(lockFor(archive)) {
        val dir = archive.absoluteFile.parentFile ?: throw IOException("${archive.name} has no folder")
        ArchiveSwap.recover(ArchiveSwap.filesIn(dir), archive.name)
        ArchiveWrites.formatRefusal(archive.name)?.let { throw IOException(it) }
        EditCosts.refusalFor(archive)?.let { throw IOException(it) }
        val format = Archives.of(archive.name) ?: throw IOException("${archive.name} is not an archive Filet knows.")
        val members = ArchiveEditor.membersOf(archive, format)
        val kind = ArchiveWrites.kindOf(format)
        for (op in ops) ArchiveWrites.opRefusal(kind, format.label, members, op)?.let { throw IOException(it) }
        val outcomes = ArchivePlan.apply(members, ops)
        ArchiveEditor.rewrite(archive, format, members, outcomes, progress, cancelled)
        // Forgotten whatever the clock and the length say: a rewrite inside the same second at the
        // same length would otherwise keep the old listing.
        MemberCache.forget(archive)
    }
}
