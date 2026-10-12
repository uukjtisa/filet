package dev.niccc2007.filet.ops

import dev.niccc2007.filet.vfs.VPath

/**
 * A clipboard that holds more than one thing.
 *
 * ## The report
 *
 * Copying from several places meant pasting after every one of them, because each Copy
 * replaced the last. The ask was for the copies to accumulate, to be pasted individually or
 * all at once, and for a copy to survive being pasted so it can go in several places.
 *
 * ## Why the unit is a batch and not a flat list of files
 *
 * Flattening is the obvious move and it is wrong twice over. A move and a copy cannot be
 * merged - they are different verbs with different consequences, so a flat list would need a
 * verb per file and pasting it would be two operations pretending to be one. And provenance
 * is how two batches are told apart: "7 from DCIM/Camera" is legible where fourteen filenames
 * are not.
 *
 * Nothing is lost by grouping, because a batch opens: [pick] and [unpick] choose files
 * inside one, and [without] removes a file from it for good. Those are different intentions
 * and they read differently - not pasting a file this time is not the same as taking it off
 * the clipboard.
 *
 * ## Copy survives, move does not, and that is physics
 *
 * A MOVE batch is single use. After it is pasted the source files are gone, so it cannot be
 * pasted again and it is dropped. That is not a policy and must not become a setting.
 *
 * A COPY batch stays until it is cleared, which is the request. Each paste adds to
 * [ClipBatch.pastedTo], so the tray can say where it has already been - the feedback that
 * makes "it is still loaded" obvious rather than something to be discovered.
 *
 * Pure, and tested, because every rule here is one that silently does the wrong thing rather
 * than failing loudly.
 */
data class ClipBatch(
    val id: String,
    /** Everything in the batch, including files not currently picked. */
    val items: List<VPath>,
    val op: PendingOp,
    /** The common parent, for the subtitle. Null when the items came from several places. */
    val from: VPath?,
    val addedAt: Long,
    /**
     * Which of [items] the next paste will take.
     *
     * Everything, until somebody says otherwise. An empty set means a batch that is loaded
     * and currently contributes nothing, which is a state the tray shows rather than hides:
     * a batch that vanished because its last tick came off would look like a bug.
     */
    val picked: Set<VPath> = items.toSet(),
    /** Where this batch has already landed. Only ever grows, and only for a copy. */
    val pastedTo: List<VPath> = emptyList(),
) {
    val size: Int get() = items.size
    val pickedCount: Int get() = picked.size
    val allPicked: Boolean get() = picked.size == items.size
    val nothingPicked: Boolean get() = picked.isEmpty()
    val isMove: Boolean get() = op == PendingOp.MOVE

    /** What the next paste would actually carry, in the batch's own order. */
    val payload: List<VPath> get() = items.filter { it in picked }
}

/**
 * The whole clipboard.
 *
 * Ordered, oldest first, because that is the order a paste-all runs in and an order that
 * changes under you is worse than one you did not choose.
 */
data class ClipTray(val batches: List<ClipBatch> = emptyList()) {

    val isEmpty: Boolean get() = batches.isEmpty()
    val size: Int get() = batches.size

    /** Every file the whole tray would paste right now. */
    val totalPicked: Int get() = batches.sumOf { it.pickedCount }

    /** Every file held, picked or not, which is what the header counts. */
    val totalItems: Int get() = batches.sumOf { it.size }

    /**
     * Add a batch.
     *
     * Two collisions are resolved here rather than at paste time, because both of them
     * silently do the wrong thing instead of failing:
     *
     *  - **A path already queued to MOVE** cannot be in a second batch. The first paste takes
     *    it away and the second fails with "no longer there", which reads as a bug in Filet
     *    rather than as two instructions that could not both be true. The new batch drops it,
     *    and the count of what was dropped comes back so the caller can say so once.
     *  - **A batch left with nothing** after that is not added at all. An empty batch is a row
     *    that can never do anything.
     *
     * The same path in two COPY batches is harmless and is left alone: copying a file to two
     * places is the feature, not a mistake.
     */
    fun add(batch: ClipBatch): Added {
        val queuedToMove = batches.filter { it.isMove }.flatMap { it.items }.toSet()
        val kept = batch.items.filterNot { batch.isMove && it in queuedToMove }
        val dropped = batch.items.size - kept.size
        if (kept.isEmpty()) return Added(this, dropped, added = false)
        val clean = batch.copy(items = kept, picked = kept.toSet())
        return Added(copy(batches = batches + clean), dropped, added = true)
    }

    data class Added(val tray: ClipTray, val droppedAlreadyQueued: Int, val added: Boolean)

    fun remove(id: String): ClipTray = copy(batches = batches.filterNot { it.id == id })

    fun clear(): ClipTray = ClipTray()

    fun batch(id: String): ClipBatch? = batches.firstOrNull { it.id == id }

    private fun map(id: String, f: (ClipBatch) -> ClipBatch): ClipTray =
        copy(batches = batches.map { if (it.id == id) f(it) else it })

    /** Tick one file. */
    fun pick(id: String, path: VPath): ClipTray = map(id) { it.copy(picked = it.picked + path) }

    /** Untick one file. The batch stays, holding it, unpicked. */
    fun unpick(id: String, path: VPath): ClipTray = map(id) { it.copy(picked = it.picked - path) }

    fun pickAll(id: String): ClipTray = map(id) { it.copy(picked = it.items.toSet()) }

    fun pickNone(id: String): ClipTray = map(id) { it.copy(picked = emptySet()) }

    /**
     * Take a file off the clipboard for good.
     *
     * Distinct from unticking, and the tray says so: unticking is "not this time", removing
     * is "I did not mean to pick this up". Removing the last file removes the batch, because
     * a batch of nothing is a row that cannot act.
     */
    fun without(id: String, path: VPath): ClipTray {
        val b = batch(id) ?: return this
        val left = b.items.filterNot { it == path }
        if (left.isEmpty()) return remove(id)
        return map(id) { it.copy(items = left, picked = it.picked - path) }
    }

    /**
     * What a paste leaves behind.
     *
     * A move is consumed; a copy stays and records where it went. A PARTLY failed batch keeps
     * its failed items and stays in the tray, which is the rule that matters most: a
     * clipboard that empties itself after a half-failure is how somebody loses track of what
     * actually moved.
     *
     * @param failed the paths the operation could not take. A move whose every item failed is
     *   not consumed, because nothing was taken away.
     */
    fun afterPaste(id: String, into: VPath, failed: Set<VPath> = emptySet()): ClipTray {
        val b = batch(id) ?: return this
        val moved = b.payload.filterNot { it in failed }
        return when {
            b.isMove && moved.isEmpty() -> this
            b.isMove && failed.isEmpty() -> remove(id)
            // A move that half worked keeps exactly what is still there to move.
            b.isMove -> {
                val left = b.items.filter { it in failed }
                if (left.isEmpty()) remove(id) else map(id) {
                    it.copy(items = left, picked = left.toSet())
                }
            }
            else -> map(id) { it.copy(pastedTo = it.pastedTo + into) }
        }
    }

    /** Whether pasting everything is worth offering. One batch is just that batch's button. */
    val worthPastingAll: Boolean get() = batches.count { it.pickedCount > 0 } > 1

    /**
     * The summary under the paste-all button: what the whole tray is about to do.
     *
     * Counts the two verbs separately, because "paste 11" over a tray holding a move is a
     * promise about three files that is not a copy.
     */
    fun allSummary(): String {
        val copied = batches.filterNot { it.isMove }.sumOf { it.pickedCount }
        val movedCount = batches.filter { it.isMove }.sumOf { it.pickedCount }
        return listOfNotNull(
            "$copied copied".takeIf { copied > 0 },
            "$movedCount moved".takeIf { movedCount > 0 },
        ).joinToString(", ")
    }
}
