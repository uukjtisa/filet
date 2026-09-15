package dev.niccc2007.filet.vfs.provider

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException

/**
 * What saving one edited member back into an archive will actually cost.
 *
 * The answer when asked whether to allow editing in place was to do it **and show the cost
 * first**, and that is the only version of this feature that is safe to ship. Saving into a zip
 * rewrites one entry; saving into a 7z rewrites the entire archive, because a solid 7z is one
 * compression stream and changing a byte in the middle means redoing all of it. Those two are
 * a keystroke apart in the UI and minutes apart in reality.
 *
 * So the sentence in front of Save is not decoration - it is the difference between somebody
 * choosing to wait and somebody wondering why the phone got hot.
 *
 * @param rewritten how many members go through a compressor. One for a patch, all of them for
 *   a rebuild.
 * @param rewrittenBytes the uncompressed weight of those members, which is what the time is
 *   roughly proportional to.
 */
data class EditCost(
    val mode: EditMode,
    val formatLabel: String,
    val members: Int,
    val totalBytes: Long,
    val rewritten: Int,
    val rewrittenBytes: Long,
) {
    /** True when saving costs about as much as making the archive again. */
    val isWholeArchive: Boolean get() = mode == EditMode.REBUILD
}

/**
 * Measuring and describing that cost, and refusing the cases that cannot be done honestly.
 *
 * Pure enough to test: [describe] takes a value and returns a sentence, and [refusalFor] takes
 * a file and returns either null or the reason in words. Neither writes anything.
 */
object EditCosts {

    /**
     * The sentence shown before Save.
     *
     * Written to be read by somebody who does not know what solid compression is, which is why
     * the 7z case says *why* rather than only *how long*: "this is how 7z works" stops it
     * reading as a bug in Filet and stops the next person filing it as one.
     */
    fun describe(cost: EditCost): String = when (cost.mode) {
        EditMode.COPY_ONLY ->
            "Filet can read ${cost.formatLabel} but not write it, so this can only be saved somewhere else."

        EditMode.PATCH -> if (cost.members <= 1) {
            "Rewrites this file. Nothing else in the archive is touched."
        } else {
            "Rewrites 1 of ${cost.members} files (${bytes(cost.rewrittenBytes)}). " +
                "The other ${cost.members - 1} are copied across without being re-compressed."
        }

        EditMode.REBUILD ->
            "Rebuilds the whole archive - all ${cost.members} files, ${bytes(cost.totalBytes)} - " +
                "because ${cost.formatLabel} is one compression stream, so changing anything in it " +
                "means writing all of it again."
    }

    /**
     * Why this archive cannot be saved back into, or null when it can.
     *
     * The encrypted case is the one that will be got wrong, and getting it wrong is the worst
     * outcome in this round: rewriting one entry into an AES zip without the password produces
     * an archive whose other entries are still encrypted and whose new one is not, which opens
     * in nothing and looks like corruption. Refusing is not a limitation to apologise for - it
     * is the difference between a refusal and a lost archive.
     */
    fun refusalFor(archive: File): String? {
        val format = Archives.of(archive.name) ?: return "${archive.name} is not an archive Filet knows."
        if (ArchiveCapabilities.editModeFor(archive.name) == EditMode.COPY_ONLY) {
            return "Filet can read ${format.label} but not write it, so this can only be saved somewhere else."
        }
        return when (format.kind) {
            ArchiveKind.ZIP -> if (zipIsEncrypted(archive)) ENCRYPTED_ZIP else null
            ArchiveKind.SEVEN_ZIP -> if (sevenZipIsEncrypted(archive)) ENCRYPTED_7Z else null
            // Neither tar nor a bare compressed stream has encryption in the format at all, so
            // there is nothing here that could be silently dropped.
            else -> null
        }
    }

    const val ENCRYPTED_ZIP =
        "This zip is encrypted. Filet cannot rewrite one file inside it without dropping the " +
            "protection on the rest, so save the edited file somewhere else instead."

    const val ENCRYPTED_7Z =
        "This 7z is encrypted. Filet cannot rebuild it without the password, so save the edited " +
            "file somewhere else instead."

    /**
     * Measure the archive.
     *
     * Reads the index, never the content: a zip's central directory and a 7z's header both say
     * how many members there are and how big they are without decompressing a byte. The tar
     * family is the exception - it has no index, so counting means reading the stream - which is
     * exactly why the answer for tar is a rebuild anyway.
     */
    @Throws(IOException::class)
    fun costOf(archive: File, member: String): EditCost {
        val format = Archives.of(archive.name)
            ?: throw IOException("${archive.name} is not an archive Filet knows.")
        val mode = ArchiveCapabilities.editModeFor(archive.name)
        var members = 0
        var total = 0L
        var memberBytes = 0L
        val want = member.trim('/')

        when (format.kind) {
            ArchiveKind.ZIP -> ZipFile.builder().setFile(archive).get().use { zf ->
                for (e in zf.entries) {
                    if (e.isDirectory) continue
                    members++
                    val size = e.size.coerceAtLeast(0L)
                    total += size
                    if (e.name.trim('/') == want) memberBytes = size
                }
            }
            ArchiveKind.SEVEN_ZIP -> SevenZFile.builder().setFile(archive).get().use { sz ->
                for (e in sz.entries) {
                    if (e.isDirectory) continue
                    members++
                    val size = e.size.coerceAtLeast(0L)
                    total += size
                    if (e.name.trim('/') == want) memberBytes = size
                }
            }
            // A stream format holds exactly one member by construction, and a tar has no index
            // to read. Both are honest as one-member archives of the file's own size: the tar
            // case understates the count, and the sentence for a rebuild leads with the bytes.
            else -> {
                members = 1
                total = archive.length()
                memberBytes = archive.length()
            }
        }

        val rewritten = if (mode == EditMode.REBUILD) members else 1
        val rewrittenBytes = if (mode == EditMode.REBUILD) total else memberBytes
        return EditCost(mode, format.label, members, total, rewritten, rewrittenBytes)
    }

    /**
     * Is any entry in this zip encrypted?
     *
     * The general-purpose bit is in the central directory, so this costs an index read rather
     * than a decompression, and it is set per entry - an archive where only one file is
     * protected is a real thing and must refuse just the same.
     */
    private fun zipIsEncrypted(archive: File): Boolean = runCatching {
        ZipFile.builder().setFile(archive).get().use { zf ->
            zf.entries.asSequence().any { it.generalPurposeBit.usesEncryption() }
        }
    }.getOrDefault(false)

    /**
     * Is this 7z encrypted?
     *
     * Opening one with an encrypted header throws before a single entry is visible, and that
     * throw IS the answer. An archive with a readable header but encrypted content reports
     * `hasStream` entries that cannot be decoded, so the header read alone is not enough and
     * the failure is treated as encrypted rather than as a broken file - the safe direction,
     * since both end in "save it somewhere else".
     */
    private fun sevenZipIsEncrypted(archive: File): Boolean = runCatching {
        SevenZFile.builder().setFile(archive).get().use { it.entries.count() }
        false
    }.getOrDefault(true)

    private fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.0f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
        else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
    }
}
