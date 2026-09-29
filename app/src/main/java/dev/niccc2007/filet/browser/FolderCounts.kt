package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many entries each folder holds, read once and remembered.
 *
 * The entry row shows a count where a file shows its size, and a count has to be read - there is
 * no field on a directory that carries it. Done carelessly that is a directory read per row on
 * every scroll frame, which is exactly the kind of thing that makes a list stutter.
 *
 * Three things keep it cheap, and they are the same three [dev.niccc2007.filet.media.Thumbnails]
 * settled on for previews:
 *
 *  - **Only what is on screen.** A row asks when it composes, so a folder nobody has scrolled to
 *    is never read.
 *  - **Remembered across scrolls**, keyed on the folder's own mtime, so scrolling back up costs
 *    nothing and a folder whose contents changed is re-read rather than stale.
 *  - **Bounded concurrency.** A phone's storage has a queue depth in single digits, and firing
 *    fifteen reads at once makes every one of them slower.
 *
 * Failures are cached too. A folder that cannot be read will not become readable by being asked
 * again every frame, and a retry loop on a permission error is indistinguishable from a hang.
 */
object FolderCounts {

    /** Null means "asked, and there is no answer" - unreadable, or not a directory. */
    private val cache = HashMap<Key, Int?>()

    private data class Key(val path: VPath, val mtime: Long)

    /** Matches FEED_PARALLELISM for the same reason: storage, not CPU, is the limit. */
    private val gate = Semaphore(4)

    suspend fun of(vfs: Vfs, path: VPath, mtime: Long): Int? {
        val key = Key(path, mtime)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val n = gate.withPermit { runCatching { vfs.countChildren(path) }.getOrNull() }
        synchronized(cache) { cache[key] = n }
        return n
    }

    /** Forget everything. For a refresh, where the point is to re-read. */
    fun clear() = synchronized(cache) { cache.clear() }
}

/**
 * The count for one folder row, or null until it is known.
 *
 * Returns null rather than zero while reading, and the row shows nothing for null - "0 items" on
 * a folder that is merely unread is a confident wrong answer in a column somebody checks before
 * deleting something.
 */
@Composable
fun rememberFolderCount(vfs: Vfs, path: VPath, mtime: Long, enabled: Boolean): Int? {
    if (!enabled) return null
    val inspecting = LocalInspectionMode.current
    var count by remember(path, mtime) { mutableStateOf<Int?>(null) }
    LaunchedEffect(path, mtime) {
        if (inspecting) return@LaunchedEffect
        count = FolderCounts.of(vfs, path, mtime)
    }
    return count
}
