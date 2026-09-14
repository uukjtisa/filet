package dev.niccc2007.filet.vfs.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.Zip4jConfig
import net.lingala.zip4j.model.ZipModel
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import net.lingala.zip4j.model.enums.EncryptionMethod as Zip4jEncryption

/**
 * Writing a zip, with the things `java.util.zip` cannot do.
 *
 * `java.util.zip.ZipOutputStream` has no password support of any kind and no multi-volume
 * support, so round 9's two zip asks - *"optional password"* and *"a multi part archive"* - are
 * both outside it. zip4j has both, under Apache-2.0, in pure Java.
 *
 * ## One writer, not two
 *
 * The plain path moved here as well, rather than keeping `java.util.zip` for unencrypted zips
 * and zip4j for the rest. Two writers for one format is how the two drift: the entry naming,
 * the store-versus-deflate decision and the directory convention would then exist twice, and
 * the second copy is the one nobody re-reads. The existing round-trip tests are what prove the
 * swap did not change what a plain zip looks like.
 *
 * ## Still stored, not deflated, for media
 *
 * The rule from the old writer is kept: an entry whose extension is already compressed to
 * within a percent of its entropy is STORED. Re-deflating a 300 MB mp4 costs minutes and saves
 * nothing. That still costs a second read of the source to learn its true length, because a
 * size from `stat` can be stale and a stored entry's header is believed by every reader.
 */
internal object ZipWriter {

    /**
     * Write [sources] as a zip to a stream.
     *
     * @throws IllegalArgumentException when [options] asks for splitting - that needs a real
     *   file, because a volume set is several files rather than one stream.
     */
    suspend fun write(
        out: OutputStream,
        sources: List<ArchiveSource>,
        options: ArchiveOptions,
        onEntry: (String) -> Unit,
    ) {
        require(!options.isSplit) { "a split zip is several files; use the file path" }
        ZipOutputStream(out.buffered(), options.password).use { zos ->
            writeEntries(zos, sources, options, onEntry)
        }
    }

    /**
     * Write [sources] as a zip volume set: `name.z01`, `name.z02`, ... and `name.zip` last.
     *
     * The parts are NOT a byte split - the final `.zip` carries the central directory - so the
     * writer has to know its own part size up front. That is the whole reason this path needs a
     * `File`: zip4j opens the next volume itself as each one fills.
     */
    suspend fun writeSplit(
        file: File,
        sources: List<ArchiveSource>,
        options: ArchiveOptions,
        onEntry: (String) -> Unit,
    ) {
        val partBytes = options.splitBytes ?: error("writeSplit without a part size")
        val model = ZipModel().apply {
            zipFile = file
            isSplitArchive = true
            splitLength = partBytes
        }
        val config = Zip4jConfig(StandardCharsets.UTF_8, BUFFER, true)
        SplitOutputStream(file, partBytes).use { split ->
            ZipOutputStream(split, options.password, config, model).use { zos ->
                writeEntries(zos, sources, options, onEntry)
            }
        }
    }

    private suspend fun writeEntries(
        zos: ZipOutputStream,
        sources: List<ArchiveSource>,
        options: ArchiveOptions,
        onEntry: (String) -> Unit,
    ) {
        val capability = ArchiveCapabilities.of(Archives.ALL.first { it.id == "zip" })
        val level = levelOf(options.levelFor(capability) ?: ArchiveCapabilities.DEFLATE.default)
        val buf = ByteArray(BUFFER)

        for (s in sources) {
            currentCoroutineContext().ensureActive()
            onEntry(s.entryPath)

            val params = ZipParameters()
            params.compressionLevel = level
            if (options.hasPassword) {
                params.isEncryptFiles = true
                when (options.encryption ?: EncryptionMethod.AES_256) {
                    EncryptionMethod.AES_256 -> {
                        params.encryptionMethod = Zip4jEncryption.AES
                        params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    EncryptionMethod.AES_128 -> {
                        params.encryptionMethod = Zip4jEncryption.AES
                        params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_128
                    }
                    // Offered only because some very old tools read nothing else. It is broken
                    // and labelled broken wherever it is shown.
                    EncryptionMethod.ZIP_CRYPTO -> params.encryptionMethod = Zip4jEncryption.ZIP_STANDARD
                }
            }

            if (s.isDir) {
                // The trailing separator IS the directory marker in a zip. Without it the entry
                // is a zero-length file with a folder's name.
                params.fileNameInZip = s.entryPath.trimEnd('/') + "/"
                params.compressionMethod = CompressionMethod.STORE
                zos.putNextEntry(params)
                zos.closeEntry()
                continue
            }

            val open = s.open ?: continue
            params.fileNameInZip = s.entryPath
            if (s.mtime > 0) params.lastModifiedFileTime = s.mtime

            val stored = s.entryPath.substringAfterLast('.', "").lowercase() in ArchiveWriter.ALREADY_COMPRESSED
            if (stored) {
                params.compressionMethod = CompressionMethod.STORE
                // Measured rather than taken from `stat`: a stored entry states its length in
                // its own header and every reader believes it, so a file that changed since it
                // was listed would corrupt everything after it. This is why `open` may be
                // called twice for these, which `ArchiveSource` documents.
                var size = 0L
                open().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                    }
                }
                params.entrySize = size
            } else {
                params.compressionMethod = CompressionMethod.DEFLATE
            }

            zos.putNextEntry(params)
            open().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    zos.write(buf, 0, n)
                }
            }
            zos.closeEntry()
        }
    }

    /**
     * Deflate level 0-9 to zip4j's enum.
     *
     * zip4j takes a `CompressionLevel` rather than an int, and its ten constants do not map
     * one-to-one onto 0-9 by ordinal. Matched on each constant's own `level`, with the nearest
     * as a fallback, so a future zip4j that renames or reorders them still lands somewhere
     * sensible instead of on whatever happens to be `entries[n]`.
     */
    internal fun levelOf(level: Int): CompressionLevel =
        CompressionLevel.entries.firstOrNull { it.level == level }
            ?: CompressionLevel.entries.minBy { kotlin.math.abs(it.level - level) }

    private const val BUFFER = 64 * 1024
}
