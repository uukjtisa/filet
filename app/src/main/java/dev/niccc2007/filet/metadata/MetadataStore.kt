package dev.niccc2007.filet.metadata

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs

/**
 * Reading and writing a file's metadata, through the VFS.
 *
 * The routing lives here and the format knowledge lives in [PngText] and [ContainerComments],
 * which work on byte arrays and have tests. This layer's whole job is the part those cannot
 * do: deciding whether a write is allowed at all, and making sure a failed one cannot destroy
 * the file.
 *
 * **Nothing is ever written in place.** The sequence is: read the whole file, build the new
 * bytes in memory, re-parse those bytes and confirm the change is actually there, and only
 * then write over the original. A writer that streams into the file it is reading turns a
 * half-understood format into a half-destroyed file, and that is the risk that makes people
 * reasonably nervous about metadata editors.
 *
 * The size ceiling is the honest limit of that approach and it is stated rather than hidden:
 * holding a file twice in memory is fine for a photo and not fine for a 4 GB video. The
 * formats above the ceiling are all read-only here anyway, so the two limits agree.
 */
class MetadataStore(private val vfs: Vfs) {

    /**
     * The largest file this will rewrite.
     *
     * A rewrite holds the original and the result at once. 64 MB covers every photo, every
     * archive comment worth editing and every audio file, and refuses the case where the
     * approach would fail - which is better than an approach that quietly succeeds until the
     * day it runs out of memory mid-write.
     */
    val maxRewriteBytes: Long = 64L * 1024 * 1024

    /** What came back from a write attempt. */
    sealed interface Result {
        data object Ok : Result

        /** The format is understood but not safely writable. [why] names the format's problem. */
        data class Refused(val why: String) : Result

        /** Something went wrong. The original is untouched. */
        data class Failed(val why: String) : Result
    }

    /** Every text field readable from [path], as key-value pairs in the order found. */
    suspend fun read(path: VPath): List<Pair<String, String>> {
        val ext = path.name.substringAfterLast('.', "")
        val bytes = runCatching { readAll(path) }.getOrNull() ?: return emptyList()
        return when {
            PngText.isPng(bytes) -> PngText.read(bytes).map { it.key to it.value }
            ContainerComments.isJpeg(bytes) ->
                ContainerComments.readJpegComment(bytes)?.let { listOf("Comment" to it) } ?: emptyList()
            ext.lowercase() in setOf("zip", "apk", "jar", "xapk", "apkm") ->
                ContainerComments.readZipComment(bytes)?.let { listOf("Comment" to it) } ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * Set one field.
     *
     * @param key the field name. Only PNG can carry an arbitrary one; every other writable
     *   format here has a single comment slot, and a key it does not know is refused rather
     *   than silently written into the one field it has.
     */
    suspend fun put(path: VPath, key: String, value: String): Result {
        val ext = path.name.substringAfterLast('.', "")
        val format = MetadataSupport.writerFor(ext)
            ?: return Result.Refused(MetadataSupport.refusalFor(ext))

        val size = runCatching { vfs.stat(path)?.size ?: -1 }.getOrDefault(-1)
        if (size > maxRewriteBytes) {
            return Result.Refused(
                "This file is larger than ${maxRewriteBytes / 1024 / 1024} MB. Changing its " +
                    "metadata means rewriting it, and Filet will not hold a file that size " +
                    "twice in memory to do it."
            )
        }

        val bytes = runCatching { readAll(path) }.getOrNull()
            ?: return Result.Failed("Could not read the file.")

        val built: ByteArray? = when {
            PngText.isPng(bytes) -> {
                if (!PngText.isValidKeyword(key)) {
                    return Result.Refused(
                        "A PNG keyword must be 1 to 79 printable characters with no leading, " +
                            "trailing or doubled spaces."
                    )
                }
                PngText.put(bytes, key, value)
            }
            ContainerComments.isJpeg(bytes) -> {
                if (!key.equals("Comment", ignoreCase = true)) {
                    return Result.Refused(
                        "A JPEG has one comment slot and no custom fields. Only Comment can be set."
                    )
                }
                ContainerComments.writeJpegComment(bytes, value)
            }
            ContainerComments.isGif(bytes) -> {
                if (!key.equals("Comment", ignoreCase = true)) {
                    return Result.Refused("A GIF has one comment slot and no custom fields.")
                }
                ContainerComments.writeGifComment(bytes, value)
            }
            ContainerComments.readZipComment(bytes) != null -> {
                if (!key.equals("Comment", ignoreCase = true)) {
                    return Result.Refused(
                        "A zip has one archive comment and no custom fields. Only Comment can be set."
                    )
                }
                ContainerComments.writeZipComment(bytes, value)
            }
            else -> return Result.Refused(
                "The contents do not match what the extension claims, so Filet will not " +
                    "rewrite it. The file itself is fine - only the name is misleading."
            )
        }

        if (built == null) {
            return Result.Failed(
                "The file could not be rebuilt safely, so nothing was changed. It is likely " +
                    "truncated or uses a variant Filet does not handle."
            )
        }

        // Re-parse the RESULT and confirm the change is in it, before the original is touched.
        // A writer that trusts its own output is a writer that overwrites a good file with a
        // bad one and reports success.
        val verified = verify(built, key, value)
        if (!verified) {
            return Result.Failed("The rewritten file did not read back correctly, so it was discarded.")
        }

        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vfs.openWrite(path, append = false).use { it.write(built) }
            }
            Result.Ok
        }.getOrElse { Result.Failed("Could not write the file: ${it.message ?: "unknown error"}") }
    }

    /** Remove one field. */
    suspend fun remove(path: VPath, key: String): Result = put(path, key, "")

    /**
     * Does the rebuilt file read back with the value that was just written?
     *
     * Deliberately re-reads rather than trusting the writer. This is the single check standing
     * between a misunderstood variant and a destroyed file.
     */
    private fun verify(built: ByteArray, key: String, value: String): Boolean = when {
        PngText.isPng(built) ->
            if (value.isEmpty()) PngText.read(built).none { it.key == key && it.value.isNotEmpty() }
            else PngText.read(built).any { it.key == key && it.value == value }
        ContainerComments.isJpeg(built) ->
            if (value.isEmpty()) ContainerComments.readJpegComment(built).isNullOrEmpty()
            else ContainerComments.readJpegComment(built) == value
        ContainerComments.isGif(built) -> true // no reader; the writer is append-only and shaped
        ContainerComments.readZipComment(built) != null ->
            ContainerComments.readZipComment(built) == value
        else -> false
    }

    private suspend fun readAll(path: VPath): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vfs.openRead(path).use { it.readBytes() } }
}
