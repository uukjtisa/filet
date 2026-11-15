package dev.niccc2007.filet.storage

import dev.niccc2007.filet.browser.LoopGuard
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One walk of a drive into a [SizeMap]: every folder listed once, every file added to the folders
 * above it, and each folder settled the moment the last folder inside it is done.
 *
 * Depth first with a stack of its own, so a deep tree cannot overflow the thread's. A folder that
 * refuses a listing - `Android/data` does on every phone since 11 - is counted in [unreadable],
 * settled as what could be read, and walked past: a disk map that stopped at the first locked
 * folder would never map the phone's own storage.
 *
 * Settling is the half that needs care. A folder is final only when it AND every folder beneath it
 * have been listed, which on a depth-first walk is long after the folder's own listing. Each
 * folder keeps a count of subfolders still open; the last one to finish settles its parent, and
 * that can settle the parent's parent, up the chain. The root settles last, and only once.
 *
 * The listing comes in as [list] rather than as the VFS itself so the order of a walk can be
 * tested without one. Loops through links are stopped by [LoopGuard], the same guard the folder
 * size sheet uses.
 */
class StorageWalk(
    private val list: suspend (VPath) -> List<VNode>,
    /** Milliseconds, for spacing [run]'s ticks. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val tickMs: Long = TICK_MS,
) {

    /** Folders that refused a listing. Their size is what could be read, a floor. */
    var unreadable = 0
        private set

    /**
     * Walks [root] into [map]. [lock] is held around each folder's changes to the map, so a reader
     * holding it sees whole folders only; [onTick] is called at most every [tickMs] while the walk
     * runs and once at the end. Cancellable between folders.
     */
    suspend fun run(root: VPath, map: SizeMap, lock: Any = map, onTick: () -> Unit = {}) {
        val guard = LoopGuard()
        val open = HashMap<String, Int>()
        val parentOf = HashMap<String, String>()
        val stack = ArrayDeque<Pair<VPath, Int>>()
        stack.addLast(root to 0)
        var lastTick = clock()

        fun finish(dir: String) {
            map.settle(dir)
            var p = parentOf.remove(dir)
            while (p != null) {
                val left = (open[p] ?: 1) - 1
                if (left > 0) {
                    open[p] = left
                    return
                }
                open.remove(p)
                map.settle(p)
                p = parentOf.remove(p)
            }
        }

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            val key = dir.toString()
            val children = try {
                list(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Everything a row costs that is not the map - its path printed, its kind looked up -
            // is done before the lock, and the folder goes in as one batch under it. Readers wait
            // on this lock; see SizeMap.addFiles.
            val files = ArrayList<SizeMap.Big>()
            val subs = ArrayList<VPath>()
            val subKeys = ArrayList<String>()
            for (c in children.orEmpty()) {
                if (c.isDir) {
                    if (!guard.enter(c.inode, depth + 1)) continue
                    subs += c.path
                    subKeys += c.path.toString()
                } else {
                    files += SizeMap.Big(c.path.toString(), c.size, SizeKind.of(FileTypes.kindOf(c.extension)))
                }
            }
            synchronized(lock) {
                if (children == null) {
                    unreadable++
                    finish(key)
                    return@synchronized
                }
                map.addFolders(subKeys)
                map.addFiles(files)
                if (subs.isEmpty()) finish(key) else open[key] = subs.size
            }
            for (i in subs.indices) {
                parentOf[subKeys[i]] = key
                stack.addLast(subs[i] to depth + 1)
            }
            val now = clock()
            if (now - lastTick >= tickMs) {
                lastTick = now
                onTick()
            }
        }
        onTick()
    }

    companion object {
        /** How often a running walk redraws the list: often enough to look alive, not every folder. */
        const val TICK_MS = 250L
    }
}
