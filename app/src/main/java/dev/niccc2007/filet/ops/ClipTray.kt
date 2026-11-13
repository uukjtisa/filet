package dev.niccc2007.filet.ops

import dev.niccc2007.filet.vfs.VPath

/**
 * A clipboard that holds more than one thing, and remembers what it has done.
 *
 * ## Batches, not a flat list of files
 *
 * Flattening is the obvious move and it is wrong twice over. A move and a copy cannot be
 * merged - they are different verbs with different consequences, so a flat list would need a
 * verb per file and pasting it would be two operations pretending to be one. And provenance
 * is how two batches are told apart: "7 from DCIM/Camera" is legible where fourteen filenames
 * are not. A batch opens: [ClipTray.pick] and [ClipTray.unpick] choose files inside one, and
 * [ClipTray.without] removes a file from it for good.
 *
 * ## New and history
 *
 * A batch is [fresh] until its first paste, and then it is history: still on the clipboard,
 * still one tap from being used again. Bug identified here: the clipboard was a pill that never
 * went away while a copy was loaded and vanished the moment a move landed, so it said nothing
 * about what had been done and kept a full-width button over the dock for something already
 * pasted. Splitting new from done is what lets the dock show the pill only for new things and a
 * small circle for the rest.
 *
 * ## A move follows its files
 *
 * After a move, the batch's paths are the files' new paths and [from] is where they are now, so
 * moving it again moves them from there. A move is not single use any more: what stops a second
 * paste is the files being somewhere else, and the batch knows where.
 *
 * Pure, and tested, because every rule here is one that silently does the wrong thing rather
 * than failing loudly.
 */
data class ClipBatch(
    val id: String,
    /** Everything in the batch, including files not currently picked. Where they are now. */
    val items: List<VPath>,
    val op: PendingOp,
    /**
     * The common parent: where a copy came from, where a move's files are now. Null when the
     * items are in several places.
     */
    val from: VPath?,
    val addedAt: Long,
    /**
     * Which of [items] the next paste will take. Everything, until somebody says otherwise. An
     * empty set means a batch that is loaded and currently contributes nothing, which the
     * clipboard shows rather than hides.
     */
    val picked: Set<VPath> = items.toSet(),
    /** Where this batch has been pasted, oldest first. */
    val pastedTo: List<VPath> = emptyList(),
    /** Not pasted yet. See the class notes. */
    val fresh: Boolean = true,
    /** When it was last pasted, for ordering history newest first. 0 while fresh. */
    val usedAt: Long = 0L,
) {
    val size: Int get() = items.size
    val pickedCount: Int get() = picked.size
    val allPicked: Boolean get() = picked.size == items.size
    val nothingPicked: Boolean get() = picked.isEmpty()
    val isMove: Boolean get() = op == PendingOp.MOVE

    /** What the next paste would actually carry, in the batch's own order. */
    val payload: List<VPath> get() = items.filter { it in picked }

    /** Whether this move's files are already in [folder], where moving them again does nothing. */
    fun isIn(folder: VPath): Boolean = isMove && !fresh && from == folder
}

/**
 * The whole clipboard. Ordered oldest first, which is the order a paste of everything new runs
 * in; the sheet shows history newest first on its own.
 */
data class ClipTray(val batches: List<ClipBatch> = emptyList()) {

    val isEmpty: Boolean get() = batches.isEmpty()
    val size: Int get() = batches.size

    /** Not pasted yet, oldest first. What the pill pastes. */
    val fresh: List<ClipBatch> get() = batches.filter { it.fresh }

    /** Pasted at least once, newest first. What the circle holds. */
    val history: List<ClipBatch> get() = batches.filterNot { it.fresh }.sortedByDescending { it.usedAt }

    /** Every file the new batches would paste right now. */
    val freshPicked: Int get() = fresh.sumOf { it.pickedCount }

    /** Every new batch is a move, so the pill says Move rather than Paste. */
    val freshAllMoves: Boolean get() = fresh.isNotEmpty() && fresh.all { it.isMove }

    /** Every file the whole tray would paste right now. */
    val totalPicked: Int get() = batches.sumOf { it.pickedCount }

    /** Every file held, picked or not. */
    val totalItems: Int get() = batches.sumOf { it.size }

    /**
     * Add a batch.
     *
     * A path already in a NEW move cannot go in a second one: the first paste takes it away and
     * the second fails with "no longer there", which reads as a bug rather than as two
     * instructions that could not both be true. The new batch drops it, and the count comes back
     * so the caller can say so once. History does not count: a move that has landed is not
     * queued, and moving one of its files again is the ordinary thing to do. A batch left with
     * nothing is not added at all.
     */
    fun add(batch: ClipBatch): Added {
        val queuedToMove = fresh.filter { it.isMove }.flatMap { it.items }.toSet()
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

    fun pick(id: String, path: VPath): ClipTray = map(id) { it.copy(picked = it.picked + path) }

    fun unpick(id: String, path: VPath): ClipTray = map(id) { it.copy(picked = it.picked - path) }

    fun pickAll(id: String): ClipTray = map(id) { it.copy(picked = it.items.toSet()) }

    fun pickNone(id: String): ClipTray = map(id) { it.copy(picked = emptySet()) }

    /**
     * Take a file off the clipboard for good. Removing the last file removes the batch, because
     * a batch of nothing is a row that cannot act.
     */
    fun without(id: String, path: VPath): ClipTray {
        val b = batch(id) ?: return this
        val left = b.items.filterNot { it == path }
        if (left.isEmpty()) return remove(id)
        return map(id) { it.copy(items = left, picked = it.picked - path) }
    }

    /**
     * What a paste of batch [id] into [into] leaves behind.
     *
     * - A copy goes to history and records where it went. A copy that copied nothing stays new:
     *   nothing was used, and history is a record of what was.
     * - A move goes to history holding the files' new paths, so it can be moved again from there.
     * - What a move did NOT take - files unticked, files that failed - stays a new batch at the
     *   old paths, ticked as it was. A clipboard that empties itself after a half-failure is how
     *   somebody loses track of what actually moved. The part that moved becomes its own history
     *   row, named [splitId].
     * - A move that took nothing changes nothing.
     *
     * [landed] is where each moved file actually went. A paste renames on a clash, so the folder
     * plus the old name is only the fallback for a source that did not report it.
     *
     * History is then held to [HISTORY_MAX], oldest dropped.
     */
    fun afterPaste(
        id: String,
        into: VPath,
        failed: Set<VPath> = emptySet(),
        now: Long = 0L,
        splitId: String = "$id-moved",
        landed: Map<VPath, VPath> = emptyMap(),
    ): ClipTray {
        val b = batch(id) ?: return this
        if (!b.isMove) {
            if (b.payload.all { it in failed }) return this
            return map(id) { it.copy(pastedTo = it.pastedTo + into, fresh = false, usedAt = now) }.capped()
        }
        val moved = b.payload.filterNot { it in failed }
        if (moved.isEmpty()) return this
        val arrived = moved.mapNotNull { landed[it] ?: it.name.takeIf { n -> n.isNotEmpty() }?.let(into::child) }
        val left = b.items.filterNot { it in moved }
        val done = b.copy(
            items = arrived,
            picked = arrived.toSet(),
            from = into,
            pastedTo = b.pastedTo + into,
            fresh = false,
            usedAt = now,
        )
        if (left.isEmpty()) return map(id) { done }.capped()
        val rest = b.copy(items = left, picked = b.picked.filter { it in left }.toSet())
        return copy(batches = batches.flatMap { if (it.id == id) listOf(rest, done.copy(id = splitId)) else listOf(it) }).capped()
    }

    /**
     * "Copy here instead" on a move: the files are copied and stay where they are, and the row
     * goes to history saying so. Nothing about the batch's paths changes, because nothing moved.
     * A copy that copied nothing leaves the row as it was.
     */
    fun afterCopyInstead(id: String, into: VPath, failed: Set<VPath> = emptySet(), now: Long = 0L): ClipTray {
        val b = batch(id) ?: return this
        if (b.payload.all { it in failed }) return this
        return map(id) { it.copy(pastedTo = it.pastedTo + into, fresh = false, usedAt = now) }.capped()
    }

    /** History held to [HISTORY_MAX], the oldest uses dropped. New batches are never dropped. */
    fun capped(max: Int = HISTORY_MAX): ClipTray {
        val keep = history.take(max).map { it.id }.toSet()
        return copy(batches = batches.filter { it.fresh || it.id in keep })
    }

    /** Whether "Bring all new here" is worth offering. One new batch is just that row's button. */
    val worthPastingAll: Boolean get() = fresh.count { it.pickedCount > 0 } > 1

    /** "2 copied, 1 moved" for [list], each verb counted on its own. */
    fun summary(list: List<ClipBatch> = fresh): String {
        val copied = list.filterNot { it.isMove }.sumOf { it.pickedCount }
        val movedCount = list.filter { it.isMove }.sumOf { it.pickedCount }
        return listOfNotNull(
            "$copied copied".takeIf { copied > 0 },
            "$movedCount moved".takeIf { movedCount > 0 },
        ).joinToString(", ")
    }

    /** The summary under the bring-all button: what the new batches are about to do. */
    fun allSummary(): String = summary(fresh)

    companion object {
        /** History kept, newest first. Approved with the mock. */
        const val HISTORY_MAX = 30
    }
}

/**
 * The clipboard as a string, so it survives Filet being closed.
 *
 * A format of its own rather than JSON: `org.json` is a stub in the unit-test jar, and this is
 * the part most worth testing off the phone. Control characters separate the parts because a
 * path can hold a tab, a comma or a newline but these three are not legal in a name on any
 * filesystem Filet reaches. Anything unreadable - a path that no longer parses, a batch from a
 * newer format - is dropped rather than refused, so a bad save costs one row and never the app.
 */
object ClipStore {
    private const val VERSION = "clip1"
    private const val BATCH = '\u001D'
    private const val FIELD = '\u001E'
    private const val ITEM = '\u001F'

    fun encode(tray: ClipTray): String = buildString {
        append(VERSION)
        for (b in tray.batches) {
            append(BATCH)
            append(
                listOf(
                    b.id,
                    b.op.name,
                    if (b.fresh) "1" else "0",
                    b.addedAt.toString(),
                    b.usedAt.toString(),
                    b.from?.toString().orEmpty(),
                    b.items.joinToString(ITEM.toString()),
                    b.picked.joinToString(ITEM.toString()),
                    b.pastedTo.joinToString(ITEM.toString()),
                ).joinToString(FIELD.toString()),
            )
        }
    }

    fun decode(raw: String?): ClipTray {
        if (raw.isNullOrEmpty()) return ClipTray()
        val parts = raw.split(BATCH)
        if (parts.first() != VERSION) return ClipTray()
        fun paths(s: String): List<VPath> =
            if (s.isEmpty()) emptyList() else s.split(ITEM).mapNotNull { runCatching { VPath.parse(it) }.getOrNull() }
        val batches = parts.drop(1).mapNotNull { line ->
            runCatching {
                val f = line.split(FIELD)
                val items = paths(f[6])
                if (items.isEmpty()) return@runCatching null
                ClipBatch(
                    id = f[0],
                    items = items,
                    op = PendingOp.valueOf(f[1]),
                    from = f[5].takeIf { it.isNotEmpty() }?.let { VPath.parse(it) },
                    addedAt = f[3].toLong(),
                    picked = paths(f[7]).filter { it in items }.toSet(),
                    pastedTo = paths(f[8]),
                    fresh = f[2] == "1",
                    usedAt = f[4].toLong(),
                )
            }.getOrNull()
        }
        return ClipTray(batches)
    }
}
