package dev.niccc2007.filet.ops

import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.ArchiveEntry
import dev.niccc2007.filet.vfs.provider.CollisionChoice
import dev.niccc2007.filet.vfs.provider.ExtractOptions
import dev.niccc2007.filet.vfs.provider.ExtractPlan
import dev.niccc2007.filet.vfs.provider.ExtractPlanner
import dev.niccc2007.filet.vfs.provider.PlannedItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Extraction, in two halves: work out what will happen, then do exactly that.
 *
 * Reported by Nic in round 9: extracting an archive he expected to produce
 * `<archive name>/contents` instead scattered the contents straight into the destination. He
 * asked for a confirmation showing what the result will look like BEFORE it runs.
 *
 * The design rule that follows from that, and the reason this file is split the way it is:
 * **[plan] produces an [ExtractPlan], the preview draws that value, and [run] walks that same
 * value.** The extractor does not re-derive the wrap or the strip. If it did, the preview would
 * be one implementation of the decision and the extraction another, and they would agree right
 * up until the archive that made them disagree - which nobody would notice until afterwards,
 * because by then the files are already on disk.
 *
 * Everything here goes through the VFS (PLAN.md R3), so an archive on a network share extracts
 * to an SD card with no branch per backend.
 */
class ExtractOperations(private val vfs: Vfs, private val ledger: JobLedger) {

    /**
     * Read the archive and work out what extracting it into [into] would produce.
     *
     * @param archiveRoot the mounted archive, e.g. `zip:///…/photos.rar!/`.
     * @param label the archive's file name, which is where a wrap folder gets its name.
     */
    suspend fun plan(
        archiveRoot: VPath,
        into: VPath,
        label: String,
        options: ExtractOptions = ExtractOptions(),
    ): ExtractPlan {
        val entries = ArrayList<ArchiveEntry>()
        walk(archiveRoot, "", entries)
        return ExtractPlanner.plan(
            archiveName = label,
            entries = entries,
            existingPaths = existingUnder(into),
            // The destination's OWN volume, not the device. Extracting to an SD card has to ask
            // the SD card, and a null answer (a network share, some document providers) means
            // unknown rather than zero - the plan treats -1 as "do not block on this".
            freeBytes = runCatching { vfs.freeSpace(into) }.getOrNull() ?: -1L,
            options = options,
        )
    }

    /** Every entry in the archive, with paths relative to its root. */
    private suspend fun walk(root: VPath, prefix: String, out: MutableList<ArchiveEntry>) {
        currentCoroutineContext().ensureActive()
        val kids = runCatching { vfs.list(root) }.getOrElse { return }
        for (node in kids) {
            val rel = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
            out += ArchiveEntry(rel, node.isDir, node.size.coerceAtLeast(0L))
            if (node.isDir) walk(node.path, rel, out)
        }
    }

    /**
     * What is already at the destination, as paths relative to it.
     *
     * Bounded on purpose. A collision check that walks an enormous destination turns opening
     * the preview into a job of its own, and the collisions that actually matter are shallow -
     * a tarbomb lands at the top level, and a wrap folder collides with a folder of the same
     * name. [SCAN_CAP] entries is far past any real case; beyond it the plan simply reports
     * fewer collisions rather than taking a minute to open.
     */
    private suspend fun existingUnder(into: VPath): Set<String> {
        val out = HashSet<String>()
        suspend fun rec(dir: VPath, prefix: String, depth: Int) {
            if (out.size >= SCAN_CAP || depth > SCAN_DEPTH) return
            currentCoroutineContext().ensureActive()
            val kids = runCatching { vfs.list(dir) }.getOrElse { return }
            for (node in kids) {
                if (out.size >= SCAN_CAP) return
                val rel = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
                out += rel
                if (node.isDir) rec(node.path, rel, depth + 1)
            }
        }
        rec(into, "", 0)
        return out
    }

    /**
     * Carry out [plan] exactly.
     *
     * Folders first and in path order, so a file is never written before the folder it goes in.
     * A failure on one entry is recorded and the rest continue: a partial extraction that says
     * which three files failed is far more useful than one that stops at the first and leaves
     * the folder half full with no account of it.
     */
    suspend fun run(archiveRoot: VPath, into: VPath, plan: ExtractPlan, label: String): OpResult {
        val id = ledger.start("Extracting", label)
        val failed = ArrayList<Pair<VPath, String>>()
        var done = 0
        val total = plan.items.count { !it.isDir }

        try {
            for (item in plan.items.sortedWith(compareByDescending(PlannedItem::isDir).thenBy(PlannedItem::path))) {
                currentCoroutineContext().ensureActive()
                val dest = childOf(into, item.path)

                if (item.isDir) {
                    runCatching { if (vfs.stat(dest) == null) vfs.create(dest, isDir = true) }
                        .onFailure { failed += dest to FileOperations.readable(it) }
                    continue
                }

                // SKIP is the only choice that declines to write. OVERWRITE and KEEP BOTH both
                // write - the difference is the path the plan already chose for them, which is
                // why there is no second decision here.
                if (item.collides == CollisionChoice.SKIP) continue

                ledger.progress(id, if (total > 0) done.toFloat() / total else null, item.path)
                runCatching {
                    // The parent may be implicit: plenty of archives store no folder records.
                    dest.parent?.let { if (vfs.stat(it) == null) vfs.create(it, isDir = true) }
                    val from = childOf(archiveRoot, item.source)
                    vfs.openRead(from).use { input ->
                        vfs.openWrite(dest).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                }.onSuccess { done++ }.onFailure { failed += dest to FileOperations.readable(it) }
            }
        } catch (e: Throwable) {
            ledger.fail(id, FileOperations.readable(e))
            return OpResult(done, failed + (archiveRoot to FileOperations.readable(e)))
        }

        if (failed.isEmpty()) ledger.finish(id, "$done file(s)")
        else ledger.fail(id, "${failed.size} failed, $done succeeded")
        return OpResult(done, failed)
    }

    /** Walk a relative path down from [base], one segment at a time. */
    private fun childOf(base: VPath, relative: String): VPath =
        relative.split('/').filter { it.isNotEmpty() }.fold(base) { acc, seg -> acc.child(seg) }

    private companion object {
        const val SCAN_CAP = 20_000
        const val SCAN_DEPTH = 12
    }
}
