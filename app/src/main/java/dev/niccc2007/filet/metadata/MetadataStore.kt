package dev.niccc2007.filet.metadata

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs

/**
 * Reading and writing a file's metadata, through the VFS.
 *
 * The format knowledge lives in the containers listed by [MetadataSupport], each of which works on
 * byte arrays and has tests. This layer's whole job is the part those cannot do: finding the right
 * one, deciding whether a write is allowed at all, and making sure a failed one cannot destroy the
 * file.
 *
 * **Nothing is ever written in place.** The sequence is: read the whole file, build the new
 * bytes in memory, re-parse those bytes and confirm the change is actually there, and only
 * then write over the original. A writer that streams into the file it is reading turns a
 * half-understood format into a half-destroyed file, and that is the risk that makes people
 * reasonably nervous about metadata editors.
 *
 * The size ceiling is the honest limit of that approach and it is stated rather than hidden:
 * holding a file twice in memory is fine for a photo and not fine for a 4 GB video.
 *
 * ## Why there is no chain of format conditions here any more
 *
 * There used to be three of them - one in `read`, one in `put`, one in `verify` - and a fourth
 * list of formats in the support table. Adding a format meant four edits, and three times over a
 * format was added to the table and to none of the rest, which is how MP3 came to advertise cover
 * art that no code could read. Routing now asks [MetadataSupport] which containers recognise the
 * file and lets each one answer for itself.
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

        /** Nothing needed doing: the file already said this. */
        data object Unchanged : Result

        /** The format is understood but not safely writable. [why] names the format's problem. */
        data class Refused(val why: String) : Result

        /** Something went wrong. The original is untouched. */
        data class Failed(val why: String) : Result
    }

    /**
     * Every readable field, in the order found, from every container that recognises the file.
     *
     * Several can at once, and that is the point: a photo out of a camera has both a JPEG comment
     * and an EXIF block, and showing one of the two because the chain of conditions stopped at
     * the first match is how half a file's metadata stays invisible.
     */
    suspend fun read(path: VPath): List<Pair<String, String>> {
        val bytes = runCatching { readAll(path) }.getOrNull() ?: return emptyList()
        return read(path.name.substringAfterLast('.', ""), bytes)
    }

    /** The same, on bytes already in hand. */
    fun read(ext: String, bytes: ByteArray): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, String>()
        for (format in MetadataSupport.forFile(ext, bytes)) {
            for ((key, value) in runCatching { format.engine.read(bytes) }.getOrDefault(emptyList())) {
                // First container to claim a label keeps it, and the list is ordered most
                // capable first - so a writable comment is the one shown as editable rather
                // than a read-only namesake from another block in the same file.
                if (value.isNotBlank()) seen.putIfAbsent(key, value)
            }
        }
        return seen.toList()
    }

    /**
     * Set one field.
     *
     * @param key the field label as the format names it. A key the container does not know is
     *   refused rather than written into whichever slot happens to be nearest.
     */
    suspend fun put(path: VPath, key: String, value: String): Result =
        rewrite(path) { format, bytes ->
            format.engine.put(bytes, key, value) to { built: ByteArray ->
                format.engine.verify(built, key, value)
            }
        }.first

    /** Remove one field. */
    suspend fun remove(path: VPath, key: String): Result = put(path, key, "")

    // ── cover art ─────────────────────────────────────────────────────────────────────────

    /** The attached picture, if this file has one. */
    suspend fun cover(path: VPath): Cover? {
        val bytes = runCatching { readAll(path) }.getOrNull() ?: return null
        val ext = path.name.substringAfterLast('.', "")
        return MetadataSupport.forFile(ext, bytes)
            .firstNotNullOfOrNull { runCatching { it.engine.cover(bytes) }.getOrNull() }
    }

    /**
     * Replace the attached picture, or remove it when given null.
     *
     * Verified the same way a field is: the rebuilt file has to read back with a picture of
     * exactly the bytes handed in, or it is discarded. A cover is the one piece of metadata big
     * enough that a length mistake shows up as a file that will not play rather than as a wrong
     * line of text, so it gets the same gate and not a weaker one.
     */
    suspend fun putCover(path: VPath, cover: Cover?): Result =
        rewrite(path) { format, bytes ->
            if (!format.binary) {
                null to { _: ByteArray -> false }
            } else {
                format.engine.putCover(bytes, cover) to { built: ByteArray ->
                    val back = format.engine.cover(built)
                    if (cover == null) back == null else back?.bytes?.contentEquals(cover.bytes) == true
                }
            }
        }.first

    suspend fun removeCover(path: VPath): Result = putCover(path, null)

    /**
     * Write the attached picture out beside the file.
     *
     * The extension comes from the image's own bytes rather than from what the tag claims, because
     * taggers get that wrong often enough to matter - a cover saved as `.png` that is really a
     * JPEG opens in nothing on a phone.
     */
    suspend fun extractCover(path: VPath): VPath? {
        val cover = cover(path) ?: return null
        val parent = path.parent ?: return null
        val stem = path.name.substringBeforeLast('.', path.name)
        val target = parent.child(stem + "-cover" + cover.extension)
        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vfs.openWrite(target, append = false).use { it.write(cover.bytes) }
            }
            target
        }.getOrNull()
    }

    // ── the one write path ────────────────────────────────────────────────────────────────

    /**
     * Read, build, verify, replace - the sequence every write goes through, written once.
     *
     * [build] returns the new bytes and the check to run against them. Returning null for the
     * bytes means the container refused the change, which is a different outcome from failing to
     * produce them and is reported differently.
     */
    private suspend fun rewrite(
        path: VPath,
        build: (MetadataSupport.Format, ByteArray) -> Pair<ByteArray?, (ByteArray) -> Boolean>,
    ): Pair<Result, MetadataSupport.Format?> {
        val ext = path.name.substringAfterLast('.', "")

        val size = runCatching { vfs.stat(path)?.size ?: -1 }.getOrDefault(-1)
        if (size > maxRewriteBytes) {
            return Result.Refused(
                "This file is larger than ${maxRewriteBytes / 1024 / 1024} MB. Changing its " +
                    "metadata means rewriting it, and Filet will not hold a file that size " +
                    "twice in memory to do it."
            ) to null
        }

        val bytes = runCatching { readAll(path) }.getOrNull()
            ?: return Result.Failed("Could not read the file.") to null

        val format = MetadataSupport.writerFor(ext, bytes)
            ?: return formatRefusal(ext, bytes) to null

        // What this one file makes impossible, as opposed to what the format makes impossible.
        format.engine.refusal(bytes)?.let { return Result.Refused(it) to format }

        val (built, check) = build(format, bytes)
        if (built == null) {
            return Result.Refused(fieldRefusal(format)) to format
        }
        if (built.contentEquals(bytes)) return Result.Unchanged to format

        // Re-parse the RESULT and confirm the change is in it, before the original is touched.
        // A writer that trusts its own output is a writer that overwrites a good file with a
        // bad one and reports success.
        if (!runCatching { check(built) }.getOrDefault(false)) {
            return Result.Failed(
                "The rewritten file did not read back correctly, so it was discarded and the " +
                    "original is untouched."
            ) to format
        }

        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vfs.openWrite(path, append = false).use { it.write(built) }
            }
            Result.Ok as Result
        }.getOrElse {
            Result.Failed("Could not write the file: ${it.message ?: "unknown error"}")
        } to format
    }

    /** Why no container will write this file at all. */
    private fun formatRefusal(ext: String, bytes: ByteArray): Result.Refused {
        val recognised = MetadataSupport.forFile(ext, bytes)
        if (recognised.isNotEmpty()) {
            return Result.Refused(recognised.first().caveat ?: MetadataSupport.refusalFor(ext))
        }
        if (MetadataSupport.forExtension(ext).isNotEmpty()) {
            return Result.Refused(
                "The contents do not match what the extension claims, so Filet will not " +
                    "rewrite it. The file itself is fine - only the name is misleading."
            )
        }
        return Result.Refused(MetadataSupport.refusalFor(ext))
    }

    /** Why this format will not take that particular field. */
    private fun fieldRefusal(format: MetadataSupport.Format): String = if (format.custom) {
        "${format.name} could not store that field. The name may contain a character the " +
            "format reserves."
    } else {
        "${format.name} has a fixed set of fields and no slot for a name of your own. " +
            "Filet writes " + format.fields.joinToString(", ") + "."
    }

    private suspend fun readAll(path: VPath): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vfs.openRead(path).use { it.readBytes() } }
}
