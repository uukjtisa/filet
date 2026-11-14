package dev.niccc2007.filet.storage

import dev.niccc2007.filet.browser.FolderBytes
import dev.niccc2007.filet.browser.FolderTotal
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.types.FileTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The disk maps that exist right now, one per drive. Two panes on one drive share a walk, and a
 * drive walked once is not walked again when Disk map is turned off and on.
 */
class DiskMaps(private val vfs: Vfs, private val scope: CoroutineScope) {

    private val maps = HashMap<String, DiskMap>()

    /** The map for the drive at [root], walking it if it has never been walked. */
    fun of(root: VPath): DiskMap = synchronized(maps) {
        maps.getOrPut(root.toString()) { DiskMap(root, vfs, scope).also { it.walk() } }
    }

    /** The map already made for [root], without starting one. */
    fun existing(root: VPath): DiskMap? = synchronized(maps) { maps[root.toString()] }

    /**
     * [gone] were deleted or moved away: off every map that counted them, at once. A folder takes
     * everything inside it; a file comes off by its own size, which the list knew.
     */
    fun removed(gone: Collection<VNode>) {
        val all = synchronized(maps) { maps.values.toList() }
        for (m in all) m.removed(gone)
    }
}

/**
 * One drive's walk and what it found. The walk runs on its own and can be stopped; readers ask
 * through [read], which holds the same lock the walk holds around each folder, and redraw on
 * [revision].
 */
class DiskMap internal constructor(val root: VPath, private val vfs: Vfs, private val scope: CoroutineScope) {

    private val map = SizeMap(root.toString())
    private var job: Job? = null
    private var walker: StorageWalk? = null

    private val _revision = MutableStateFlow(0)

    /** Moves whenever the map changes, for anything drawn from it. */
    val revision: StateFlow<Int> = _revision

    private val _walking = MutableStateFlow(false)
    val walking: StateFlow<Boolean> = _walking

    /** Whether the last walk was stopped part way, so every figure is a floor until walked again. */
    @Volatile var stopped = false
        private set

    /** Folders that refused a listing, from the last walk. */
    val unreadable: Int get() = walker?.unreadable ?: 0

    /** Starts a walk from scratch, stopping one that is running. */
    fun walk() {
        job?.cancel()
        stopped = false
        synchronized(map) { map.clear() }
        val w = StorageWalk({ vfs.list(it) })
        walker = w
        _walking.value = true
        job = scope.launch(Dispatchers.IO) {
            try {
                w.run(root, map) { _revision.update { it + 1 } }
            } finally {
                _walking.value = false
                _revision.update { it + 1 }
            }
        }
    }

    /** Stops the walk where it is. What it read stays, as floors. */
    fun stop() {
        if (job?.isActive == true) stopped = true
        job?.cancel()
    }

    /** Reads the map under its lock. Short reads only: the walk waits while this runs. */
    fun <T> read(block: (SizeMap) -> T): T = synchronized(map) { block(map) }

    /** The walked size of [node]: a folder's total, or a file's own size. Null when not reached. */
    fun sizeOf(node: VNode): Long? =
        if (node.isDir) read { it.bytes(node.path.toString()) } else node.size.takeIf { it >= 0 }

    internal fun removed(gone: Collection<VNode>) {
        var changed = false
        read { m ->
            for (n in gone) {
                val p = n.path.toString()
                if (!p.startsWith(root.toString())) continue
                if (n.isDir) m.remove(p) else m.removeFile(p, n.size.coerceAtLeast(0), SizeKind.of(FileTypes.kindOf(n.extension)))
                changed = true
            }
        }
        if (changed) _revision.update { it + 1 }
    }

    /**
     * The map as the file list's folder totals, so a folder row in Disk map shows what the walk
     * found, with the `+` of a floor until the walk has finished it. Never asks for a walk of its
     * own: the drive's walk is already reading everything.
     */
    fun folderBytes(): FolderBytes = object : FolderBytes {
        override suspend fun of(path: VPath): FolderTotal = read { m ->
            val bytes = m.bytes(path.toString())
            when {
                bytes == null -> FolderTotal.Unknown
                // A folder finished before a Stop is as final as any: only the rest are floors.
                m.settled(path.toString()) -> FolderTotal.Settled(bytes)
                _walking.value -> FolderTotal.Confirming(bytes)
                else -> FolderTotal.AtLeast(bytes)
            }
        }

        override fun measure(path: VPath) = Unit
    }
}
