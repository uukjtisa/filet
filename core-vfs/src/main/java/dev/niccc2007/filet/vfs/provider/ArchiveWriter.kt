package dev.niccc2007.filet.vfs.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Making an archive, in whichever format was picked.
 *
 * It sits beside the readers rather than in the app layer for one reason: the two have to
 * agree about what a member path looks like, and a writer that stores `docs\a.txt` while the
 * reader normalises to `docs/a.txt` produces an archive Filet itself cannot browse. They share
 * [normaliseMember] and a round-trip test proves it.
 *
 * Everything here reads through a supplied `open()` rather than a path, so the source can be
 * an SD card over SAF, a network share, or another archive, with no branch per backend.
 */

/**
 * One thing to put in an archive.
 *
 * @param entryPath where it goes INSIDE the archive, with forward separators and no leading
 *   one. Built by the caller, because only the caller knows which folder the selection was
 *   relative to.
 * @param open called at most once, when the entry is written. Suspending so a network read
 *   does not block the writer's thread.
 */
class ArchiveSource(
    val entryPath: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val open: (suspend () -> InputStream)? = null,
)

object ArchiveWriter {

    /**
     * Whether this archive has to be written to a real file rather than to a stream.
     *
     * Two unrelated reasons, which is why it takes the options as well as the format: 7z
     * indexes as it writes and has to seek back, and a SPLIT zip is not one stream at all -
     * zip4j opens each `.z01`, `.z02` volume itself as the previous one fills.
     */
    fun needsRealFile(format: ArchiveFormat, options: ArchiveOptions = ArchiveOptions.NONE): Boolean =
        format.kind == ArchiveKind.SEVEN_ZIP || (format.kind == ArchiveKind.ZIP && options.isSplit)

    /**
     * Extensions already compressed to within a percent of their entropy.
     *
     * Re-deflating a 300 MB mp4 costs minutes and saves nothing, so zip stores those instead.
     * The tar family gets no per-entry choice - the compressor wraps the whole stream - which
     * is a real reason to offer plain `.tar` for a folder of video.
     */
    internal val ALREADY_COMPRESSED = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "mp3", "m4a", "aac", "ogg",
        "opus", "flac", "mp4", "mkv", "webm", "mov", "avi", "zip", "7z", "rar", "gz", "bz2",
        "xz", "apk", "jar", "epub", "pdf",
    )

    /**
     * Write every source into [out].
     *
     * @param onEntry called with each entry path as it starts, for a progress line.
     * @throws IllegalArgumentException if the format needs a real file - call [writeToFile].
     */
    suspend fun writeToStream(
        format: ArchiveFormat,
        out: OutputStream,
        sources: List<ArchiveSource>,
        options: ArchiveOptions = ArchiveOptions.NONE,
        onEntry: (String) -> Unit = {},
    ) {
        require(!needsRealFile(format, options)) { "${format.label} needs a real file; use writeToFile" }
        refuseImpossibleOptions(format, options)
        when (format.kind) {
            ArchiveKind.ZIP -> ZipWriter.write(out, sources, options, onEntry)
            ArchiveKind.TAR -> writeTar(format, out, sources, options, onEntry)
            else -> throw IllegalArgumentException("${format.label} cannot be created")
        }
    }

    /**
     * Refuse a combination the format cannot honour, before a byte is written.
     *
     * The failure this prevents is the quiet one: a password handed to a writer that has no
     * encryption produces a perfectly good archive with no protection on it, and it looks
     * identical to a protected one until somebody opens it without the password. Checked here
     * rather than trusted to the dialog, because the dialog is one of two callers.
     */
    private fun refuseImpossibleOptions(format: ArchiveFormat, options: ArchiveOptions) {
        val why = options.problemFor(ArchiveCapabilities.of(format))
        require(why == null) { why!! }
    }

    /**
     * The 7z path, by OS path.
     *
     * The caller is in the app layer and may not hold a `java.io.File` - see PLAN.md R3 - so
     * it passes the spelling it got from `Vfs.osPath` and the File stops here, inside L0.
     */
    suspend fun writeToPath(
        format: ArchiveFormat,
        osPath: String,
        sources: List<ArchiveSource>,
        options: ArchiveOptions = ArchiveOptions.NONE,
        onEntry: (String) -> Unit = {},
    ) = writeToFile(format, File(osPath), sources, options, onEntry)

    /** The paths that need a real file: 7z always, and a split zip. */
    suspend fun writeToFile(
        format: ArchiveFormat,
        file: File,
        sources: List<ArchiveSource>,
        options: ArchiveOptions = ArchiveOptions.NONE,
        onEntry: (String) -> Unit = {},
    ) {
        require(needsRealFile(format, options)) { "${format.label} does not need a file" }
        refuseImpossibleOptions(format, options)
        if (format.kind == ArchiveKind.ZIP) {
            ZipWriter.writeSplit(file, sources, options, onEntry)
            return
        }
        SevenZOutputFile(file).use { z ->
            for (s in sources) {
                currentCoroutineContext().ensureActive()
                onEntry(s.entryPath)
                val entry = SevenZArchiveEntry()
                entry.name = s.entryPath
                entry.isDirectory = s.isDir
                if (!s.isDir) entry.size = s.size.coerceAtLeast(0L)
                if (s.mtime > 0) entry.lastModifiedDate = java.util.Date(s.mtime)
                z.putArchiveEntry(entry)
                if (!s.isDir) s.open?.invoke()?.use { input -> pump(input) { b, n -> z.write(b, 0, n) } }
                z.closeArchiveEntry()
            }
        }
    }

    private suspend fun writeTar(
        format: ArchiveFormat,
        out: OutputStream,
        sources: List<ArchiveSource>,
        options: ArchiveOptions,
        onEntry: (String) -> Unit,
    ) {
        // Each wrapper takes its setting in its OWN units - a gzip level, a bzip2 block size, an
        // LZMA2 preset - which is why the capability table keeps them as three separate scales
        // rather than one shared 0-9.
        val level = options.levelFor(ArchiveCapabilities.of(format))
        val compressed: OutputStream = when {
            format.id == "tar.gz" -> GzipCompressorOutputStream(
                out.buffered(),
                GzipParameters().apply { if (level != null) compressionLevel = level },
            )
            format.id == "tar.bz2" ->
                if (level != null) BZip2CompressorOutputStream(out.buffered(), level)
                else BZip2CompressorOutputStream(out.buffered())
            format.id == "tar.xz" ->
                if (level != null) XZCompressorOutputStream(out.buffered(), level)
                else XZCompressorOutputStream(out.buffered())
            else -> out.buffered()
        }
        TarArchiveOutputStream(compressed).use { tar ->
            // Paths longer than 100 characters do not fit the original header. POSIX is the
            // format every modern extractor reads; the alternative is a silent truncation
            // that renames the file.
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (s in sources) {
                currentCoroutineContext().ensureActive()
                onEntry(s.entryPath)
                val entry = TarArchiveEntry(if (s.isDir) s.entryPath.trimEnd('/') + "/" else s.entryPath)
                if (!s.isDir) entry.size = s.size.coerceAtLeast(0L)
                if (s.mtime > 0) entry.setModTime(s.mtime)
                tar.putArchiveEntry(entry)
                if (!s.isDir) {
                    // A tar header states the length up front and the reader trusts it, so a
                    // source that turns out shorter or longer than its stat would corrupt
                    // everything after it. Padding and truncating keeps the archive readable
                    // and confines the damage to the one entry.
                    var written = 0L
                    s.open?.invoke()?.use { input ->
                        pump(input) { b, n ->
                            val room = (entry.size - written).coerceAtLeast(0L)
                            val take = minOf(n.toLong(), room).toInt()
                            if (take > 0) { tar.write(b, 0, take); written += take }
                        }
                    }
                    if (written < entry.size) {
                        val filler = ByteArray(8 * 1024)
                        var left = entry.size - written
                        while (left > 0) {
                            val n = minOf(left, filler.size.toLong()).toInt()
                            tar.write(filler, 0, n)
                            left -= n
                        }
                    }
                }
                tar.closeArchiveEntry()
            }
        }
    }

    private suspend fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            sink(buf, n)
        }
    }
}

/**
 * The name a new archive gets.
 *
 * Never the name of something already there. Compress is one tap away from a selection, and a
 * second tap quietly overwriting the first archive is the way this feature loses data.
 *
 * @param base what to call it before the suffix: the folder's name, or the one file's, or
 *   "Archive" for a mixed selection.
 * @param taken whether a name already exists in the destination.
 */
fun archiveName(base: String, format: ArchiveFormat, taken: (String) -> Boolean): String {
    val clean = base.trim().ifEmpty { "Archive" }
    var n = 1
    while (true) {
        val candidate = if (n == 1) "$clean${format.suffix}" else "$clean ($n)${format.suffix}"
        if (!taken(candidate)) return candidate
        n++
        require(n < 10_000) { "no free name for $clean${format.suffix}" }
    }
}
