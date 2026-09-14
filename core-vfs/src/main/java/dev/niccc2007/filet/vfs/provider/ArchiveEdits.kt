package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.VPath
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Saving an edited member back into the archive it came from, addressed the way everything
 * above L0 addresses storage: by [VPath].
 *
 * The editor itself works on a real file, because rewriting a container means seeking in one.
 * That file handle stops here. Without this façade the view model would have to take a
 * `zip:///a/b.zip!/notes.txt` apart and build a `java.io.File` from the front half, which is a
 * raw path in L2 and exactly what PLAN.md R3 forbids - and `tools/check-r3.mjs` would say so.
 *
 * Three questions, in the order a save asks them:
 *
 *  1. [refusalFor] - can this archive hold the edit at all? (encrypted, or a format Filet only
 *     reads.) A refusal here is not a failure to apologise for: it is the difference between
 *     saying no and destroying an archive.
 *  2. [costOf] - what will it cost? One entry, or all of them.
 *  3. [replace] - do it.
 */
object ArchiveEdits {

    /** True when this path names something INSIDE an archive rather than the archive itself. */
    fun isMember(path: VPath): Boolean {
        if (path.scheme != ArchiveProvider.SCHEME) return false
        return ArchiveProvider.split(path).second.isNotEmpty()
    }

    /** What a save into this archive can offer, from the archive's name alone. */
    fun modeFor(member: VPath): EditMode =
        ArchiveCapabilities.editModeFor(File(archiveOf(member)).name)

    /** Why the edit cannot be saved back, or null when it can. */
    fun refusalFor(member: VPath): String? = EditCosts.refusalFor(File(archiveOf(member)))

    /** How much of the archive gets rewritten. */
    @Throws(IOException::class)
    fun costOf(member: VPath): EditCost {
        val (archive, inner) = ArchiveProvider.split(member)
        return EditCosts.costOf(File(archive), inner)
    }

    /**
     * Write [open]'s bytes over the member, leaving the rest of the archive alone where the
     * format allows it.
     *
     * Refuses first. Calling this without checking [refusalFor] would mean a rewrite that
     * silently drops encryption, which is the worst outcome available in this feature, so the
     * check is here and not only in the caller.
     */
    @Throws(IOException::class)
    fun replace(member: VPath, open: () -> InputStream) {
        val (archive, inner) = ArchiveProvider.split(member)
        if (inner.isEmpty()) throw IOException("that is the archive itself, not a file inside it")
        val file = File(archive)
        EditCosts.refusalFor(file)?.let { throw IOException(it) }
        ArchiveEditor.replace(file, inner, open)
    }

    /** The name of the archive the member lives in, for a sentence about it. */
    fun archiveName(member: VPath): String = File(archiveOf(member)).name

    /** Where the archive itself lives, as a VPath in whatever provider actually holds it. */
    fun archivePath(member: VPath, scheme: String = "local"): VPath {
        // The split gives back a host path, which on a drive-letter host has no leading
        // separator. A VPath is absolute by contract, so put it back the way `mount` does.
        val raw = archiveOf(member)
        return VPath.of(scheme, if (raw.startsWith("/")) raw else "/$raw")
    }

    private fun archiveOf(member: VPath): String = ArchiveProvider.split(member).first
}
