package dev.niccc2007.filet.vfs.provider

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
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

/**
 * Replacing one file inside an existing archive.
 *
 * The requirement: edit a member of an archive in place, without recompressing the whole
 * thing, and on save offer the choice between updating the archive and writing the edited
 * file somewhere else.
 *
 * The "without recompressing" part is achievable, but only for some containers, and which ones
 * is a property of the format rather than of how clever this code is. [ArchiveCapabilities]
 * answers that as [EditMode]; this carries it out.
 *
 * ## The three modes
 *
 * - **[EditMode.PATCH]** - zip and plain tar. Every untouched member is copied across WITHOUT
 *   being decoded: for a zip that is `addRawArchiveEntry` over the already-deflated bytes, so
 *   a 340-file archive re-compresses exactly one file. This is the case the requirement is about.
 * - **[EditMode.REBUILD]** - `tar.gz` and friends, and 7z. One compression stream over the
 *   whole archive, so changing a byte in the middle means redoing all of it. Not slow code;
 *   the format. The UI says so, with the size, before it starts.
 * - **[EditMode.COPY_ONLY]** - RAR. Filet cannot write it at all, so the only offer is saving
 *   the file somewhere else.
 *
 * ## Never in place
 *
 * Every path here writes a temp file beside the target and swaps it in at the end. An
 * interrupted save therefore leaves the original archive exactly as it was. Editing the
 * archive in place would mean a power cut or a cancelled job turning a working archive into a
 * truncated one, and an archive is usually the only copy of what is in it.
 */
object ArchiveEditor {

    /** What a save can offer for this archive. */
    fun modeFor(archiveName: String): EditMode = ArchiveCapabilities.editModeFor(archiveName)

    /**
     * Replace [member] inside [archive] with the bytes from [open].
     *
     * @param open called once, when the new entry is written.
     * @throws IOException with a plain sentence when the format cannot be written at all.
     */
    @Throws(IOException::class)
    fun replace(archive: File, member: String, open: () -> InputStream) {
        val format = Archives.of(archive.name)
            ?: throw IOException("${archive.name} is not an archive Filet knows.")
        val want = normaliseMember(member)

        when (modeFor(archive.name)) {
            EditMode.COPY_ONLY -> throw IOException(
                "Filet reads ${format.label} and cannot write it, so this file can only be saved somewhere else.",
            )
            EditMode.PATCH -> when (format.kind) {
                ArchiveKind.ZIP -> intoTemp(archive) { patchZip(archive, it, want, open) }
                ArchiveKind.TAR -> intoTemp(archive) { rebuildTar(format, archive, it, want, open) }
                else -> throw IOException("${format.label} cannot be edited in place.")
            }
            EditMode.REBUILD -> when (format.kind) {
                ArchiveKind.TAR -> intoTemp(archive) { rebuildTar(format, archive, it, want, open) }
                ArchiveKind.SEVEN_ZIP -> intoTemp(archive) { rebuild7z(archive, it, want, open) }
                ArchiveKind.STREAM -> intoTemp(archive) { rebuildStream(format, it, open) }
                else -> throw IOException("${format.label} cannot be edited in place.")
            }
        }
    }

    /**
     * Run [write] into a temp file and only then replace [target].
     *
     * The original is untouched until the new archive is complete on disk. On any failure the
     * temp goes and the original stays, which is the difference between a cancelled edit and a
     * lost archive.
     */
    private fun intoTemp(target: File, write: (File) -> Unit) {
        val temp = File(target.parentFile, target.name + ".editing")
        try {
            write(temp)
            if (!temp.isFile || temp.length() == 0L) throw IOException("the rewritten archive came out empty")
            val backup = File(target.parentFile, target.name + ".previous")
            if (backup.exists()) backup.delete()
            // Rename the original aside rather than deleting it, so the window in which neither
            // file exists is as close to zero as a filesystem allows.
            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("could not move the original aside")
            }
            if (!temp.renameTo(target)) {
                backup.renameTo(target)
                throw IOException("could not put the rewritten archive in place")
            }
            backup.delete()
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * A zip, with every untouched entry copied as raw compressed bytes.
     *
     * `addRawArchiveEntry` writes the entry's stored bytes straight through with its existing
     * method, CRC and sizes. . The replaced entry is the only one that goes through
     * a compressor.
     */
    private fun patchZip(archive: File, temp: File, member: String, open: () -> InputStream) {
        var seen = false
        ZipFile.builder().setFile(archive).get().use { zf ->
            ZipArchiveOutputStream(temp).use { out ->
                for (entry in zf.entries) {
                    if (normaliseMember(entry.name) == member && !entry.isDirectory) {
                        seen = true
                        val fresh = ZipArchiveEntry(entry.name)
                        fresh.method = ZipArchiveEntry.DEFLATED
                        fresh.time = System.currentTimeMillis()
                        out.putArchiveEntry(fresh)
                        open().use { it.copyTo(out) }
                        out.closeArchiveEntry()
                    } else {
                        out.addRawArchiveEntry(entry, zf.getRawInputStream(entry))
                    }
                }
            }
        }
        if (!seen) throw IOException("$member is not in ${archive.name}")
    }

    /** A tar, through whichever compressor its extension names. */
    private fun rebuildTar(
        format: ArchiveFormat,
        archive: File,
        temp: File,
        member: String,
        open: () -> InputStream,
    ) {
        var seen = false
        openTar(format, archive).use { tin ->
            wrapOut(format, temp.outputStream().buffered()).use { raw ->
                TarArchiveOutputStream(raw).use { out ->
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    var entry = tin.nextEntry
                    while (entry != null) {
                        val name = normaliseMember(entry.name)
                        if (name == member && !entry.isDirectory) {
                            seen = true
                            // A tar header states the length up front and the reader believes
                            // it, so the replacement has to be measured before it is written.
                            val bytes = open().use { it.readBytes() }
                            val fresh = TarArchiveEntry(entry.name)
                            fresh.size = bytes.size.toLong()
                            fresh.setModTime(System.currentTimeMillis())
                            out.putArchiveEntry(fresh)
                            out.write(bytes)
                            out.closeArchiveEntry()
                        } else {
                            val copy = TarArchiveEntry(entry.name)
                            copy.size = if (entry.isDirectory) 0 else entry.size
                            copy.setModTime(entry.lastModifiedDate?.time ?: 0L)
                            out.putArchiveEntry(copy)
                            if (!entry.isDirectory) tin.copyTo(out)
                            out.closeArchiveEntry()
                        }
                        entry = tin.nextEntry
                    }
                }
            }
        }
        if (!seen) throw IOException("$member is not in ${archive.name}")
    }

    /** 7z is solid by default, so this is a full rebuild whatever is replaced. */
    private fun rebuild7z(archive: File, temp: File, member: String, open: () -> InputStream) {
        var seen = false
        SevenZFile.builder().setFile(archive).get().use { z ->
            SevenZOutputFile(temp).use { out ->
                for (entry in z.entries) {
                    val name = normaliseMember(entry.name)
                    val fresh = SevenZArchiveEntry()
                    fresh.name = entry.name
                    fresh.isDirectory = entry.isDirectory
                    if (name == member && !entry.isDirectory) {
                        seen = true
                        val bytes = open().use { it.readBytes() }
                        fresh.size = bytes.size.toLong()
                        out.putArchiveEntry(fresh)
                        out.write(bytes)
                        out.closeArchiveEntry()
                    } else {
                        val bytes = if (entry.isDirectory) ByteArray(0) else z.getInputStream(entry).readBytes()
                        if (!entry.isDirectory) fresh.size = bytes.size.toLong()
                        out.putArchiveEntry(fresh)
                        if (bytes.isNotEmpty()) out.write(bytes)
                        out.closeArchiveEntry()
                    }
                }
            }
        }
        if (!seen) throw IOException("$member is not in ${archive.name}")
    }

    /** `notes.txt.gz` holds exactly one member, so replacing it IS rewriting the file. */
    private fun rebuildStream(format: ArchiveFormat, temp: File, open: () -> InputStream) {
        wrapOut(format, temp.outputStream().buffered()).use { out ->
            open().use { it.copyTo(out) }
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
}
