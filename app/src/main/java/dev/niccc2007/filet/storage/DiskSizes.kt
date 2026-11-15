package dev.niccc2007.filet.storage

import dev.niccc2007.filet.browser.FolderBytes
import dev.niccc2007.filet.browser.FolderTotal
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The figures one folder is drawn and ordered by in Disk map, copied out of the walk at one
 * moment. Immutable, so anything may read it on any thread without the walk's lock. Pure.
 *
 * Bug identified here: Disk map read the walk's map from the main thread - once per row to sort
 * the folder, once per row for each share bar and once more per row for each folder total - and
 * every one of those reads took the lock the walk holds while it adds a folder. Four times a
 * second, on every tick of the walk, the whole list was sorted, recomposed and asked again, and a
 * frame that met the walk inside a large folder waited for it to finish the folder. That was the
 * lag. The figures are now copied out once per tick, off the main thread, under one hold of the
 * lock, and everything drawn reads the copy.
 */
class DiskSizes(
    /** The folder these are the figures of. */
    val folder: VPath,
    /** [folder]'s own total, which every row's share is of. Null until the walk reaches it. */
    val total: Long?,
    /** Whether [total] is final. */
    val settledHere: Boolean,
    /** Files under [folder] read so far. */
    val filesHere: Int,
    /** Files read by the whole walk so far. */
    val filesRead: Int,
    /** Whether the walk was still running when these were read. */
    val walking: Boolean,
    private val bytes: Map<VPath, Long>,
    private val settled: Set<VPath>,
) {

    /** A folder row's total, or a file row's own size. Null for a folder the walk has not reached. */
    fun sizeOf(node: VNode): Long? = if (node.isDir) bytes[node.path] else node.size.takeIf { it >= 0 }

    /** How much of [folder] the row takes, from 0 to 1. */
    fun share(node: VNode): Float = DiskOrder.share(sizeOf(node), total)

    fun percent(node: VNode): String = DiskOrder.percent(sizeOf(node), total)

    /**
     * A folder row's total as the file list draws it: a `+` until the walk has finished the
     * folder. A folder finished before a Stop is as final as any; only the rest are floors.
     */
    fun totalOf(path: VPath): FolderTotal {
        val b = bytes[path] ?: return FolderTotal.Unknown
        return when {
            path in settled -> FolderTotal.Settled(b)
            walking -> FolderTotal.Confirming(b)
            else -> FolderTotal.AtLeast(b)
        }
    }

    companion object {
        /**
         * The figures for [rows] of [folder] out of [map]. Only folder rows are looked up; a file
         * row's size is its own. Called with the walk's lock held, so it does nothing else.
         */
        fun read(map: SizeMap, folder: VPath, here: String, dirs: List<Pair<VPath, String>>, walking: Boolean): DiskSizes {
            val bytes = HashMap<VPath, Long>(dirs.size * 2)
            val settled = HashSet<VPath>()
            for ((path, key) in dirs) {
                val b = map.bytes(key) ?: continue
                bytes[path] = b
                if (map.settled(key)) settled += path
            }
            return DiskSizes(
                folder = folder,
                total = map.bytes(here),
                settledHere = map.settled(here),
                filesHere = map.files(here),
                filesRead = map.filesRead,
                walking = walking,
                bytes = bytes,
                settled = settled,
            )
        }
    }
}

/**
 * Disk map's folder totals as the file list reads them. One for as long as the map is on, and
 * each row follows its own figure through [watch] - so a tick of the walk redraws only the rows
 * whose figure moved.
 *
 * The fault this replaces was a new reader on every tick. The reader is a static composition
 * local, so swapping it recomposed everything beneath it four times a second, and each row's
 * figure started over from nothing to ask again.
 */
class DiskTotals(private val sizes: StateFlow<DiskSizes?>) : FolderBytes {

    override suspend fun of(path: VPath): FolderTotal = sizes.value?.totalOf(path) ?: FolderTotal.Unknown

    /** Never asks for a walk of its own: the drive's walk is already reading everything. */
    override fun measure(path: VPath) = Unit

    override fun watch(path: VPath): Flow<FolderTotal> =
        sizes.map { it?.totalOf(path) ?: FolderTotal.Unknown }.distinctUntilChanged()
}
