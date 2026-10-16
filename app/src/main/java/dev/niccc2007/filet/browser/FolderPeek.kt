package dev.niccc2007.filet.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * A glance at what is inside a folder, for drawing on its icon.
 *
 * ## Why a folder needed this
 *
 * Every folder in the app looked identical, which is most of what made long lists feel
 * uniform and lifeless - reported in exactly those words. A folder of photographs and a
 * folder of invoices are not the same object and should not be the same picture. Windows
 * has done this for twenty years for the same reason.
 *
 * ## What it costs, and the three things keeping that down
 *
 * It is a directory read per folder row, which is exactly the cost `rememberFolderCount`
 * exists to be careful about, so:
 *
 *  1. **Cached on path AND mtime.** A folder that has not changed is read once, ever, for as
 *     long as the app is alive. Scrolling back up a list costs nothing.
 *  2. **Two at a time.** A screen of twelve folders would otherwise open twelve directories
 *     at once on a card that is already handing out thumbnails.
 *  3. **It stops early.** [SCAN] entries are enough to find three pictures in any folder that
 *     has them, and a folder of ten thousand files must not be walked to draw a 30dp icon.
 *
 * What comes back is child NODES rather than bitmaps: the thumbnail pipeline already decodes,
 * caches and sizes those, and a second one here would be a second cache to keep in step.
 */
class FolderPeek(private val vfs: Vfs) {

    private val cache = ConcurrentHashMap<String, List<VNode>>()
    private val gate = Semaphore(AT_ONCE)

    fun cached(path: VPath, mtime: Long): List<VNode>? = cache[key(path, mtime)]

    suspend fun of(path: VPath, mtime: Long): List<VNode> {
        val k = key(path, mtime)
        cache[k]?.let { return it }
        val found = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val out = ArrayList<VNode>(SHOW)
                    var seen = 0
                    for (child in vfs.list(path)) {
                        seen++
                        if (seen > SCAN) break
                        if (child.isDir) continue
                        if (!Thumbnails.canPreview(child.extension)) continue
                        out.add(child)
                        if (out.size >= SHOW) break
                    }
                    out as List<VNode>
                }.getOrDefault(emptyList())
            }
        }
        cache[k] = found
        return found
    }

    fun clear() = cache.clear()

    private fun key(path: VPath, mtime: Long) = "$path@$mtime"

    private companion object {
        /** How many previews are drawn on one folder. Three fan nicely; four is a mess. */
        const val SHOW = 3

        /** How far into a folder to look before giving up on finding pictures. */
        const val SCAN = 60

        /** How many folders are read at once. */
        const val AT_ONCE = 2
    }
}

/** This folder's first few previewable children, read off the main thread and remembered. */
@Composable
fun rememberFolderPeek(peek: FolderPeek, path: VPath, mtime: Long, enabled: Boolean): State<List<VNode>> =
    produceState(peek.cached(path, mtime) ?: emptyList(), path, mtime, enabled) {
        if (!enabled) return@produceState
        value = peek.of(path, mtime)
    }
